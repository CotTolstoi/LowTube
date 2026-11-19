package com.lowtube.android.data

import android.content.Context
import android.net.Uri
import com.lowtube.android.model.SearchPage
import com.lowtube.android.model.ResolvedVideoStream
import com.lowtube.android.model.SavedChannel
import com.lowtube.android.model.VideoItem
import com.lowtube.android.model.DownloadFormatOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import android.os.Build

/** Aggregate-only instrumentation hook for comparing anonymous YouTube player clients. */
internal data class PlayerClientStreamSummary(
    val client: String,
    val formatCount: Int,
    val directUrlCount: Int,
    val signatureCipherCount: Int,
    val obfuscatedSignatureCount: Int,
    val plainSignatureCount: Int,
    val directUrlNCount: Int,
    val hasDashManifest: Boolean,
    val hasHlsManifest: Boolean,
    val hasServerAbrStream: Boolean,
)

/** Guest catalogue client plus bounded public-stream resolution; private account data stays out of this layer. */
class InnerTubeSearchRepository(context: Context? = null) {
    private val operationMutex = Mutex()
    private val playerScriptCache = YoutubePlayerScriptCache()
    private val playerJavaScriptRuntime = context?.let { YoutubePlayerJavaScriptRuntime() }
    private val playerTransformCacheLock = Any()
    @Volatile private var cachedPlayerTransforms: CachedPlayerTransforms? = null
    private var clientVersion = ""
    private var apiKey = ""
    private var signatureTimestamp = 0
    private var visitorData = ""
    private var configuredAt = 0L
    private var cachedMwebClientConfiguration: MwebClientConfiguration? = null
    private var mwebConfiguredAt = 0L
    private var cooldownUntil = 0L
    private var configuredPlayerScriptUrl = ""
    internal var playerClientStreamSummaryObserver: ((PlayerClientStreamSummary) -> Unit)? = null
    internal var playerRequestOverrideForAcceptance: ((String, String, String, String) -> JSONObject)? = null

    suspend fun search(input: String, continuation: String? = null, language: String = "en"): SearchPage =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                throwIfCoolingDown()
                val query = input.trim()
                require(query.isNotEmpty()) { "Enter a video title or YouTube URL." }
                val videoLink = extractVideoLink(query)
                if (videoLink != null && continuation == null) return@withLock loadVideoMetadata(videoLink.id, language, videoLink.kind)
                val playlistId = extractPlaylistId(query)
                val channelUrl = isChannelUrl(query)
                configure(language)
                val body = JSONObject().put("context", context(language))
                val endpoint = if (playlistId != null || channelUrl) "browse" else "search"
                if (continuation.isNullOrBlank()) {
                    when {
                        playlistId != null -> body.put("browseId", "VL$playlistId")
                        channelUrl -> body.put("browse", resolveChannelBrowse(query, language))
                        else -> body.put("query", query)
                    }
                } else body.put("continuation", continuation)
                val requestBody = if (channelUrl && continuation.isNullOrBlank()) {
                    val browse = body.getJSONObject("browse")
                    JSONObject().put("context", body.getJSONObject("context")).put("browseId", browse.getString("browseId")).also { payload ->
                        browse.optString("params").takeIf(String::isNotBlank)?.let { payload.put("params", it) }
                    }
                } else body
                val root = JSONObject(request("/youtubei/v1/$endpoint?prettyPrint=false", requestBody.toString(), true))
                root.optJSONObject("responseContext")?.optString("visitorData")?.takeIf(String::isNotBlank)?.let { visitorData = it }
                parseSearchPage(root)
            }
        }

    suspend fun recommendations(
        language: String = "en",
        topic: String = "science and technology",
        discoveryLanguage: String = "any",
        continuation: String? = null,
    ): SearchPage =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                throwIfCoolingDown()
                configure(language)
                val body = JSONObject().put("context", context(language))
                if (continuation.isNullOrBlank()) body.put("query", discoveryQuery(topic, discoveryLanguage))
                else body.put("continuation", continuation)
                val root = JSONObject(request("/youtubei/v1/search?prettyPrint=false", body.toString(), true))
                root.optJSONObject("responseContext")?.optString("visitorData")?.takeIf(String::isNotBlank)?.let { visitorData = it }
                parseSearchPage(root)
            }
        }

    private fun discoveryQuery(topic: String, discoveryLanguage: String): String {
        val cleanTopic = topic.trim().take(200).ifBlank { "science and technology" }
        val term = when (discoveryLanguage) {
            "en" -> "in English"
            "uk" -> "українською"
            "ru" -> "на русском"
            "pl" -> "po polsku"
            "de" -> "auf Deutsch"
            "fr" -> "en français"
            "es" -> "en español"
            "it" -> "in italiano"
            "pt" -> "em português"
            "ja" -> "日本語"
            "ko" -> "한국어"
            else -> ""
        }
        return listOf(cleanTopic, term).filter(String::isNotBlank).joinToString(" ")
    }

    /** Resolves a public channel URL, @handle, or UC channel ID for local subscription storage. */
    suspend fun resolveChannel(input: String, language: String = "en"): SavedChannel = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val raw = input.trim()
            require(raw.isNotBlank()) { "Enter a YouTube channel URL, @handle, or channel ID." }
            val url = when {
                CHANNEL_ID.matches(raw) -> "https://www.youtube.com/channel/$raw/videos"
                raw.startsWith("@") && HANDLE.matches(raw) -> "https://www.youtube.com/$raw/videos"
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> "https://www.youtube.com/$raw"
            }
            if (!isChannelUrl(url)) throw IOException("Enter a YouTube channel URL, @handle, or channel ID.")
            configure(language)
            val browse = resolveChannelBrowse(url, language)
            val id = browse.optString("browseId")
            if (!CHANNEL_ID.matches(id)) throw IOException("YouTube could not resolve a channel ID for this address.")
            val payload = JSONObject().put("context", context(language)).put("browseId", id)
            browse.optString("params").takeIf(String::isNotBlank)?.let { payload.put("params", it) }
            val root = JSONObject(request("/youtubei/v1/browse?prettyPrint=false", payload.toString(), true))
            val title = channelTitle(root).ifBlank { id }
            SavedChannel(id, title.take(250), "https://www.youtube.com/channel/$id")
        }
    }

    /** Resolves public direct or DASH streams using watch-page data and bounded player metadata. */
    suspend fun resolvePlayback(
        video: VideoItem,
        preferences: PlaybackStreamPreferences = PlaybackStreamPreferences(),
        preferWebClient: Boolean = false,
    ): ResolvedVideoStream = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val resolved = resolvePlayerData(video, preferWebClient)
            resolveStreams(resolved.video, resolved.streaming, preferences, resolved.userAgent)
        }
    }

    private suspend fun resolvePlayerData(video: VideoItem, preferWebClient: Boolean): ResolvedPlayerData {
        require(VIDEO_ID.matches(video.id)) { "This item is not a playable video." }
        if (video.liveStatus == "is_upcoming") throw IOException("This live stream has not started yet.")
        val html = request("/watch?v=${urlEncode(video.id)}&hl=en", null, false, PLAYER_TIMEOUT_MS)
        val player = embeddedObject(html, "ytInitialPlayerResponse =")
            ?: throw IOException("YouTube did not return player data for this video.")
        val details = player.optJSONObject("videoDetails")
        if (details?.optString("videoId") != video.id) throw IOException("YouTube returned player data for a different video.")
        val pageStreaming = player.optJSONObject("streamingData")
        configure("en", PLAYER_TIMEOUT_MS)
        val playerScriptUrl = player.optJSONObject("assets")?.optString("js")
            ?.takeIf(String::isNotBlank) ?: configuredPlayerScriptUrl
        val requestSignatureTimestamp = if (pageStreaming?.let(::containsPlayerJsChallenge) == true && playerScriptUrl.isNotBlank()) {
            runCatching { playerScriptCache.load(playerScriptUrl).signatureTimestamp }.getOrNull() ?: signatureTimestamp
        } else signatureTimestamp
        // Request fresh client-bound links first; watch-page links can be stale or rejected.
        val resolvedStreaming = resolvePlayerResponse(
            video.id,
            player,
            preferWebClient,
            requestSignatureTimestamp,
        )
            .takeIf { hasDirectOrDash(it.streaming) }
            ?: pageStreaming?.takeIf(::hasDirectOrDash)?.let { ResolvedPlayerResponse(it, USER_AGENT) }
            ?: throw IOException("YouTube did not return a directly playable stream.")
        val streaming = if (containsPlayerJsChallenge(resolvedStreaming.streaming)) {
            val runtime = playerJavaScriptRuntime
                ?: throw IOException("This video requires a player signature transform, which is unavailable in this resolver context.")
            val selectedPlayerScriptUrl = resolvedStreaming.playerScriptUrl
                ?.takeIf(String::isNotBlank) ?: playerScriptUrl
            val playerScript = runCatching { playerScriptCache.load(selectedPlayerScriptUrl) }
                .getOrElse { throw IOException("YouTube's player script could not be loaded for this stream.", it) }
            val functions = playerTransforms(playerScript)
            transformPlayerChallenges(resolvedStreaming.streaming, playerScript, functions, runtime)
        } else resolvedStreaming.streaming
        val enrichedVideo = video.copy(
            title = details.optString("title").ifBlank { video.title },
            channel = details.optString("author").ifBlank { video.channel },
            channelId = details.optString("channelId").ifBlank { video.channelId },
            duration = details.optString("lengthSeconds").toLongOrNull()?.let { formatDuration(it.toString()) } ?: video.duration,
        )
        return ResolvedPlayerData(enrichedVideo, streaming, resolvedStreaming.userAgent)
    }

    /** Resolves a single-file progressive stream suitable for Android DownloadManager. */
    suspend fun resolveDownload(
        video: VideoItem,
        preferences: DownloadStreamPreferences = DownloadStreamPreferences(),
        audioOnly: Boolean = false,
    ): ResolvedVideoStream {
        return resolveDownloadOptions(video, preferences, audioOnly).firstOrNull()?.stream
            ?: throw IOException("YouTube did not return a directly downloadable format that matches these settings.")
    }

    suspend fun resolveDownloadOptions(
        video: VideoItem,
        preferences: DownloadStreamPreferences = DownloadStreamPreferences(),
        audioOnly: Boolean = false,
    ): List<DownloadFormatOption> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val resolved = resolvePlayerData(video, preferWebClient = true)
            downloadOptions(resolved.video, resolved.streaming, preferences, audioOnly, resolved.userAgent)
        }
    }

    suspend fun resolveDownloadOption(
        video: VideoItem,
        preferences: DownloadStreamPreferences,
        audioOnly: Boolean,
        key: String,
    ): DownloadFormatOption = resolveDownloadOptions(video, preferences, audioOnly)
        .firstOrNull { it.key == key }
        ?: throw IOException("The selected download format is no longer available. Choose another format.")

    internal fun downloadOptions(
        video: VideoItem,
        streaming: JSONObject,
        preferences: DownloadStreamPreferences,
        audioOnly: Boolean,
        userAgent: String = USER_AGENT,
    ): List<DownloadFormatOption> {
        val formats = parsePlaybackFormats(streaming)
        return formats.asSequence()
            .filter { if (audioOnly) it.audioOnly else it.videoMuxed }
            .filter { it.mimeType in SUPPORTED_DOWNLOAD_MIME_TYPES }
            .filter { audioOnly || it.json.optInt("height", 0) in 1..preferences.maxHeight }
            .filter { audioOnly || !preferences.h264Only || it.codec.startsWith("avc1") }
            .filter { audioOnly || !preferences.fps30Only || it.json.optDouble("fps", 0.0) <= 30.0 }
            .distinctBy { "${it.json.optInt("itag", 0)}|${it.mimeType}|$audioOnly" }
            .sortedWith(
                if (audioOnly) compareByDescending<PlaybackFormat> { it.json.optLong("bitrate", 0) }
                    .thenByDescending { it.mimeType == "audio/mp4" }
                else compareByDescending<PlaybackFormat> { it.json.optInt("height", 0) }
                    .thenByDescending { it.json.optDouble("fps", 0.0) }
                    .thenByDescending { it.json.optLong("bitrate", 0) },
            )
            .map { format ->
                val stream = format.toStream(video, isAudioOnly = audioOnly, userAgent = userAgent)
                DownloadFormatOption(
                    key = "${stream.itag}|${stream.mimeType}|$audioOnly",
                    stream = stream,
                    bitrateKbps = (format.json.optLong("bitrate", 0) / 1000L).toInt().coerceAtLeast(0),
                    container = when (format.mimeType) {
                        "video/webm", "audio/webm" -> "WebM"
                        "audio/mpeg" -> "MP3"
                        else -> if (audioOnly) "M4A" else "MP4"
                    },
                )
            }.toList()
    }

    private fun resolveStreams(
        video: VideoItem,
        streaming: JSONObject,
        preferences: PlaybackStreamPreferences,
        userAgent: String,
    ): ResolvedVideoStream {
        val formats = parsePlaybackFormats(streaming)

        val requestedVideo = formats.asSequence()
            .filter { it.videoOnly || it.videoMuxed }
            .filter { it.json.optInt("height", 0) in 1..preferences.maxHeight }
            .filter { !preferences.h264Only || it.codec.startsWith("avc1") }
            .filter { !preferences.fps30Only || it.json.optDouble("fps", 0.0) <= 30.0 }
            .sortedWith(compareByDescending<PlaybackFormat> { it.json.optInt("height", 0) }
                .thenByDescending { it.json.optLong("bitrate", 0) })
            .toList()
        val videoCandidates = requestedVideo.ifEmpty {
            formats.asSequence()
                .filter { it.videoOnly || it.videoMuxed }
                .filter { it.json.optInt("height", 0) in 1..preferences.maxHeight }
                .sortedBy { it.json.optInt("height", Int.MAX_VALUE) }
                .toList()
        }
        val audioCandidates = formats.filter { it.audioOnly }

        if (preferences.audioOnly) {
            val audio = audioCandidates.filter { it.codec.startsWith("mp4a") || it.mimeType == "audio/mp4" }
                .maxByOrNull { it.json.optLong("bitrate", 0) }
                ?: audioCandidates.maxByOrNull { it.json.optLong("bitrate", 0) }
            if (audio != null) return audio.toStream(video, isAudioOnly = true, userAgent = userAgent)
            throw IOException("YouTube did not provide a direct audio-only stream for this video.")
        }

        // Media3's DASH source selects matching separate video and audio representations.
        val dashUrl = streaming.optString("dashManifestUrl")
        if (videoCandidates.any { it.videoOnly } && audioCandidates.isNotEmpty() && isTrustedStreamUrl(dashUrl)) {
            return ResolvedVideoStream(
                uri = dashUrl,
                mimeType = "application/dash+xml",
                video = video,
                userAgent = userAgent,
                durationMs = parseDurationMs(video.duration),
                isDashManifest = true,
            )
        }

        val selected = videoCandidates.firstOrNull { it.videoMuxed }
            ?: videoCandidates.firstOrNull()
        if (selected != null) return selected.toStream(video, userAgent = userAgent)

        if (isTrustedStreamUrl(dashUrl)) return ResolvedVideoStream(
            uri = dashUrl,
            mimeType = "application/dash+xml",
            video = video,
            userAgent = userAgent,
            durationMs = parseDurationMs(video.duration),
            isDashManifest = true,
        )

        hlsManifestFallback(video, streaming, userAgent)?.let { return it }

        throw IOException("YouTube did not provide a playable stream that matches the current format preferences. This video may require a signature or client token which LowTube does not support yet.")
    }

    private fun isTrustedStreamUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = runCatching { Uri.parse(url).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
    }

    private fun parsePlaybackFormats(streaming: JSONObject): List<PlaybackFormat> {
        val formats = mutableListOf<PlaybackFormat>()
        for (key in arrayOf("formats", "adaptiveFormats")) {
            val array = streaming.optJSONArray(key) ?: continue
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val url = playableFormatUrl(json) ?: continue
                if (!isTrustedStreamUrl(url)) continue
                val mime = json.optString("mimeType").substringBefore(';')
                val codec = json.optString("mimeType").substringAfter("codecs=\"", "").substringBefore('"').substringBefore(',')
                val videoOnly = mime.startsWith("video/") && !json.has("audioQuality")
                val audioOnly = mime.startsWith("audio/")
                val videoMuxed = mime.startsWith("video/") && !videoOnly
                formats += PlaybackFormat(json, url, mime, codec, videoOnly, audioOnly, videoMuxed)
            }
        }
        return formats
    }

    private data class ResolvedPlayerData(val video: VideoItem, val streaming: JSONObject, val userAgent: String)
    private data class ResolvedPlayerResponse(
        val streaming: JSONObject,
        val userAgent: String,
        val playerScriptUrl: String? = null,
    )
    private data class MwebClientConfiguration(
        val clientVersion: String,
        val playerScriptUrl: String,
        val signatureTimestamp: Int,
    )
    private data class PlayerRequestClient(
        val name: String,
        val version: String,
        val id: String,
        val userAgent: String,
    )

    /** Accepts direct URLs and signatureCipher entries whose signature is already plain text. */
    private fun playableFormatUrl(format: JSONObject): String? {
        val direct = format.optString("url").takeIf(::isTrustedStreamUrl)
        if (direct != null) return direct

        val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
        if (cipher.isBlank() || cipher.length > MAX_CIPHER_LENGTH) return null
        val fields = runCatching { Uri.parse("https://cipher.invalid/?$cipher") }.getOrNull() ?: return null
        val base = fields.getQueryParameter("url") ?: return null
        if (!isTrustedStreamUrl(base)) return null
        val signature = fields.getQueryParameter("sig")
            ?: fields.getQueryParameter("signature")
        val obfuscatedSignature = fields.getQueryParameter("s")
        if (!obfuscatedSignature.isNullOrBlank() || signature.isNullOrBlank()) return null
        val parameter = fields.getQueryParameter("sp")?.takeIf { SIGNATURE_PARAMETER.matches(it) } ?: "signature"
        val baseUri = Uri.parse(base)
        if (baseUri.getQueryParameter(parameter) != null) return base
        val signed = baseUri.buildUpon().appendQueryParameter(parameter, signature).build().toString()
        return signed.takeIf(::isTrustedStreamUrl)
    }

    private data class PlaybackFormat(
        val json: JSONObject,
        val url: String,
        val mimeType: String,
        val codec: String,
        val videoOnly: Boolean,
        val audioOnly: Boolean,
        val videoMuxed: Boolean,
    ) {
        fun toStream(video: VideoItem, isAudioOnly: Boolean = false, userAgent: String = USER_AGENT) = ResolvedVideoStream(
            uri = url,
            mimeType = mimeType.ifBlank { if (isAudioOnly) "audio/mp4" else "video/mp4" },
            video = video,
            userAgent = userAgent,
            durationMs = video.duration.split(':').mapNotNull(String::toLongOrNull).fold(0L) { value, part -> value * 60 + part } * 1000L,
            videoHeight = json.optInt("height", 0),
            fps = json.optDouble("fps", 0.0),
            videoCodec = if (isAudioOnly) "" else codec,
            audioCodec = if (isAudioOnly) codec else "",
            itag = json.optInt("itag", 0),
            isAudioOnly = isAudioOnly,
            isMuxed = videoMuxed,
        )
    }

    internal fun hasDirectOrDash(streaming: JSONObject): Boolean {
        for (key in arrayOf("formats", "adaptiveFormats")) {
            val formats = streaming.optJSONArray(key) ?: continue
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                if (playableFormatUrl(format) != null) return true
            }
        }
        return isTrustedStreamUrl(streaming.optString("dashManifestUrl")) ||
            isTrustedStreamUrl(streaming.optString("hlsManifestUrl"))
    }

    internal fun hlsManifestFallback(video: VideoItem, streaming: JSONObject, userAgent: String = USER_AGENT): ResolvedVideoStream? {
        val hlsUrl = streaming.optString("hlsManifestUrl")
        if (!isTrustedStreamUrl(hlsUrl)) return null
        return ResolvedVideoStream(
            uri = hlsUrl,
            mimeType = "application/x-mpegURL",
            video = video,
            userAgent = userAgent,
            durationMs = parseDurationMs(video.duration),
            isHlsManifest = true,
        )
    }

    private fun containsPlayerJsChallenge(streaming: JSONObject): Boolean {
        for (key in arrayOf("formats", "adaptiveFormats")) {
            val formats = streaming.optJSONArray(key) ?: continue
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
                if (cipher.isNotBlank() && runCatching {
                        Uri.parse("https://cipher.invalid/?$cipher").getQueryParameter("s").orEmpty().isNotBlank()
                    }.getOrDefault(false)) return true
                if (runCatching { Uri.parse(format.optString("url")).getQueryParameter("n").orEmpty().isNotBlank() }.getOrDefault(false)) return true
            }
        }
        return false
    }

    private suspend fun transformPlayerChallenges(
        streaming: JSONObject,
        playerScript: YoutubePlayerScriptCache.PlayerScript,
        executableFunctions: YoutubePlayerTransformLocator.ExecutableFunctions,
        runtime: YoutubePlayerJavaScriptRuntime,
    ): JSONObject {
        val functions = executableFunctions.functions
        var activeExecutableFunctions = executableFunctions
        val transformed = JSONObject(streaming.toString())
        val pendingUrlTransforms = mutableListOf<PendingUrlTransform>()
        for (key in arrayOf("formats", "adaptiveFormats")) {
            val formats = transformed.optJSONArray(key) ?: continue
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
                if (cipher.isNotBlank()) {
                    val fields = Uri.parse("https://cipher.invalid/?$cipher")
                    val obfuscated = fields.getQueryParameter("s").orEmpty()
                    if (obfuscated.isNotBlank()) {
                        val function = functions.signature
                        if (function.isNullOrBlank()) {
                            format.remove("signatureCipher")
                            format.remove("cipher")
                            continue
                        }
                        val signatureSource = executableFunctions.signatureProgram
                            ?: throw IOException("YouTube signature transform could not be isolated from the player bundle.")
                        val signature = runCatching {
                            runtime.executeFunction(signatureSource, function, obfuscated, functions.signatureArgumentPrefix)
                        }.getOrNull()
                        val signedUrl = signature?.let { YoutubePlayerUrlTransformer.applySignatureCipher(cipher, it) }
                        if (signedUrl.isNullOrBlank() || !isTrustedStreamUrl(signedUrl)) {
                            format.remove("signatureCipher")
                            format.remove("cipher")
                            continue
                        }
                        format.put("url", signedUrl)
                        format.remove("signatureCipher")
                        format.remove("cipher")
                    }
                }

                val directUrl = format.optString("url")
                val throttled = runCatching { Uri.parse(directUrl).getQueryParameter("n") }.getOrNull().orEmpty()
                if (throttled.isNotBlank()) {
                    pendingUrlTransforms.add(PendingUrlTransform(format, directUrl))
                }
            }
        }
        val urlFunction = activeExecutableFunctions.functions.urlThrottling
        val batchResults = ArrayList<String?>(pendingUrlTransforms.size)
        if (urlFunction != null) {
            pendingUrlTransforms.chunked(MAX_URL_N_BATCH_SIZE).forEach { batch ->
                try {
                    batchResults.addAll(
                        runtime.executeFullPlayerFunctions(
                            playerScript.source,
                            batch.map { item ->
                                YoutubePlayerJavaScriptRuntime.FunctionCall(urlFunction, item.url)
                            },
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    repeat(batch.size) { batchResults.add(null) }
                }
            }
        } else {
            repeat(pendingUrlTransforms.size) { batchResults.add(null) }
        }
        val acceptedBatchResults = pendingUrlTransforms.mapIndexed { index, item ->
            batchResults.getOrNull(index)?.let { YoutubePlayerUrlTransformer.acceptUrlNTransform(item.url, it) }
        }
        val fallbackIndexes = acceptedBatchResults.indices.filter { acceptedBatchResults[it] == null }
            .take(MAX_MARKER_URL_BATCH)
        val markerBatchResults = if (fallbackIndexes.isNotEmpty() && executableFunctions.markerCandidateNames.isNotEmpty()) {
            val candidates = executableFunctions.markerCandidateNames.take(MAX_MARKER_URL_CANDIDATES)
            val rawResults = try {
                runtime.executeFullPlayerUrlObjectNTransforms(
                    playerScript.source,
                    candidates,
                    fallbackIndexes.map { pendingUrlTransforms[it].url },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            List(pendingUrlTransforms.size) { index ->
                val fallbackIndex = fallbackIndexes.indexOf(index)
                if (fallbackIndex < 0) null
                else rawResults.getOrNull(fallbackIndex)?.url?.let {
                    YoutubePlayerUrlTransformer.acceptUrlNTransform(pendingUrlTransforms[index].url, it)
                }
            }
        } else emptyList()
        pendingUrlTransforms.forEachIndexed { index, item ->
            var urlTransformed = acceptedBatchResults.getOrNull(index) ?: markerBatchResults.getOrNull(index)
            if (urlTransformed == null && urlFunction != null) {
                val attempt = executeUrlTransformWithRecovery(
                    playerScript,
                    activeExecutableFunctions,
                    item.url,
                    runtime,
                )
                activeExecutableFunctions = attempt.first
                urlTransformed = attempt.second?.let {
                    YoutubePlayerUrlTransformer.acceptUrlNTransform(item.url, it)
                }
            }
            val throttled = runCatching { Uri.parse(item.url).getQueryParameter("n") }.getOrNull().orEmpty()
            val function = activeExecutableFunctions.functions.throttling
            val throttlingSource = activeExecutableFunctions.throttlingProgram
            val result = if (urlTransformed == null && function != null && !throttlingSource.isNullOrBlank()) {
                runCatching { runtime.executeFunction(throttlingSource, function, throttled) }.getOrNull()
            } else null
            val updatedUrl = urlTransformed ?: result?.let { YoutubePlayerUrlTransformer.applyN(item.url, it) }
            if (updatedUrl.isNullOrBlank() || !isTrustedStreamUrl(updatedUrl)) item.format.remove("url")
            else item.format.put("url", updatedUrl)
        }
        if (!hasDirectOrDash(transformed)) throw IOException("YouTube player transforms could not produce a usable stream URL.")
        return transformed
    }

    private data class PendingUrlTransform(val format: JSONObject, val url: String)

    private suspend fun executeUrlTransformWithRecovery(
        playerScript: YoutubePlayerScriptCache.PlayerScript,
        initialFunctions: YoutubePlayerTransformLocator.ExecutableFunctions,
        directUrl: String,
        runtime: YoutubePlayerJavaScriptRuntime,
    ): Pair<YoutubePlayerTransformLocator.ExecutableFunctions, String?> {
        var executable = initialFunctions
        val requiredDependencies = executable.requiredDependencies.toMutableSet()
        for (attempt in 0..MAX_URL_RECOVERY_ATTEMPTS) {
            val function = executable.functions.urlThrottling ?: return executable to null
            val source = executable.urlThrottlingProgram?.takeIf(String::isNotBlank) ?: return executable to null
            try {
                return executable to runtime.executeFunction(source, function, directUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val unresolved = YoutubePlayerJavaScriptRuntime.unresolvedIdentifier(failure) ?: return executable to null
                if (!requiredDependencies.add(unresolved) || requiredDependencies.size > MAX_URL_RECOVERY_DEPENDENCIES) {
                    return executable to null
                }
                val expanded = runCatching {
                    playerTransforms(playerScript, requiredDependencies)
                }.getOrNull() ?: return executable to null
                if (expanded.urlThrottlingProgram == source) return executable to null
                executable = expanded
            }
        }
        return executable to null
    }

    private fun playerTransforms(
        playerScript: YoutubePlayerScriptCache.PlayerScript,
        requiredDependencies: Set<String> = emptySet(),
    ): YoutubePlayerTransformLocator.ExecutableFunctions {
        cachedPlayerTransforms?.takeIf {
            it.scriptUrl == playerScript.url && it.executable.requiredDependencies.containsAll(requiredDependencies)
        }?.let { return it.executable }
        synchronized(playerTransformCacheLock) {
            cachedPlayerTransforms?.takeIf {
                it.scriptUrl == playerScript.url && it.executable.requiredDependencies.containsAll(requiredDependencies)
            }?.let { return it.executable }
            val executable = YoutubePlayerTransformLocator.locateExecutable(
                playerScript.source,
                includeMarkerCandidates = true,
                includeMarkerPrograms = false,
                requiredDependencies = requiredDependencies,
            )
            cachedPlayerTransforms = CachedPlayerTransforms(playerScript.url, executable)
            return executable
        }
    }

    private data class CachedPlayerTransforms(
        val scriptUrl: String,
        val executable: YoutubePlayerTransformLocator.ExecutableFunctions,
    )

    private fun resolvePlayerResponse(
        videoId: String,
        pageResponse: JSONObject,
        preferWebClient: Boolean,
        requestSignatureTimestamp: Int,
    ): ResolvedPlayerResponse {
        if (apiKey.isBlank()) throw IOException("YouTube player configuration is incomplete. Retry the video.")
        val androidClient = PlayerRequestClient("ANDROID", "20.13.41", "3", USER_AGENT)
        val webClient = PlayerRequestClient("WEB", clientVersion, "1", USER_AGENT)
        val clients = if (preferWebClient) {
            listOf(webClient, androidClient)
        } else {
            listOf(androidClient, webClient)
        }
        var lastStatus = ""
        for ((name, version, clientId, userAgent) in clients) {
            val client = JSONObject()
                .put("clientName", name)
                .put("clientVersion", version)
                .put("hl", "en")
                .put("gl", "US")
            if (name == "ANDROID") {
                client.put("androidSdkVersion", Build.VERSION.SDK_INT)
                    .put("osName", "Android")
                    .put("osVersion", Build.VERSION.RELEASE)
                    .put("deviceMake", Build.MANUFACTURER)
                    .put("deviceModel", Build.MODEL)
            }
            if (name == "MWEB") client.put("userAgent", userAgent)
            val context = JSONObject().put("client", client)
            val payload = JSONObject()
                .put("videoId", videoId)
                .put("context", context)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
            if (requestSignatureTimestamp > 0) payload.put(
                "playbackContext",
                JSONObject().put("contentPlaybackContext", JSONObject().put("signatureTimestamp", requestSignatureTimestamp)),
            )
            try {
                val response = playerRequestOverrideForAcceptance?.invoke(
                    payload.toString(),
                    version,
                    clientId,
                    userAgent,
                ) ?: playerRequest(payload.toString(), version, clientId, userAgent)
                val status = response.optJSONObject("playabilityStatus")
                lastStatus = status?.optString("status").orEmpty()
                val streaming = response.optJSONObject("streamingData")
                if (streaming != null) playerClientStreamSummaryObserver?.invoke(playerClientStreamSummary(name, streaming))
                if (streaming != null && hasDirectOrDash(streaming)) return ResolvedPlayerResponse(streaming, userAgent)
            } catch (_: IOException) {
                // A failed anonymous client is followed by the next client.
            }
        }
        val mwebConfiguration = currentMwebClientConfiguration(videoId)
        val mwebClient = PlayerRequestClient(
            "MWEB",
            mwebConfiguration.clientVersion,
            "2",
            MWEB_USER_AGENT,
        )
        val mwebContext = JSONObject()
            .put("clientName", mwebClient.name)
            .put("clientVersion", mwebClient.version)
            .put("hl", "en")
            .put("gl", "US")
            .put("userAgent", mwebClient.userAgent)
        val mwebPayload = JSONObject()
            .put("videoId", videoId)
            .put("context", JSONObject().put("client", mwebContext))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val mwebSignatureTimestamp = mwebConfiguration.signatureTimestamp.takeIf { it > 0 }
            ?: requestSignatureTimestamp
        if (mwebSignatureTimestamp > 0) mwebPayload.put(
            "playbackContext",
            JSONObject().put("contentPlaybackContext", JSONObject().put("signatureTimestamp", mwebSignatureTimestamp)),
        )
        try {
            val response = playerRequestOverrideForAcceptance?.invoke(
                mwebPayload.toString(),
                mwebClient.version,
                mwebClient.id,
                mwebClient.userAgent,
            ) ?: playerRequest(mwebPayload.toString(), mwebClient.version, mwebClient.id, mwebClient.userAgent)
            val streaming = response.optJSONObject("streamingData")
            if (streaming != null) {
                playerClientStreamSummaryObserver?.invoke(playerClientStreamSummary(mwebClient.name, streaming))
                if (hasDirectOrDash(streaming)) {
                    return ResolvedPlayerResponse(
                        streaming,
                        mwebClient.userAgent,
                        mwebConfiguration.playerScriptUrl,
                    )
                }
            }
            lastStatus = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        } catch (_: IOException) {
            // Fall through to watch-page data or the bounded unavailable-stream message.
        }
        val streaming = pageResponse.optJSONObject("streamingData")
        if (streaming != null) return ResolvedPlayerResponse(streaming, USER_AGENT)
        throw IOException(if (lastStatus.isBlank()) "YouTube player API returned no streams. Retry on a different network." else "YouTube player API returned $lastStatus without a playable stream.")
    }

    private fun currentMwebClientConfiguration(videoId: String): MwebClientConfiguration {
        cachedMwebClientConfiguration?.takeIf {
            System.currentTimeMillis() - mwebConfiguredAt < TimeUnit.MINUTES.toMillis(15)
        }?.let { return it }

        val connection = (URL("https://m.youtube.com/watch?v=${urlEncode(videoId)}&hl=en")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = PLAYER_TIMEOUT_MS
            readTimeout = PLAYER_TIMEOUT_MS
            setRequestProperty("User-Agent", MWEB_USER_AGENT)
            setRequestProperty("Accept", "text/html,*/*;q=0.8")
            setRequestProperty("Referer", "https://m.youtube.com/")
        }
        try {
            if (connection.responseCode in 200..299) {
                val html = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_MWEB_CONFIG_BYTES) throw IOException("YouTube mobile configuration is too large.")
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val version = MWEB_CLIENT_VERSION_PATTERN.find(html)?.groupValues?.getOrNull(1).orEmpty()
                if (version.isNotBlank()) {
                    val scriptUrl = MWEB_PLAYER_SCRIPT_PATTERN.find(html)?.groupValues?.getOrNull(1)
                        .orEmpty().ifBlank { configuredPlayerScriptUrl }
                    val signatureTimestamp = MWEB_SIGNATURE_TIMESTAMP_PATTERN.find(html)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val configuration = MwebClientConfiguration(version, scriptUrl, signatureTimestamp)
                    cachedMwebClientConfiguration = configuration
                    mwebConfiguredAt = System.currentTimeMillis()
                    return configuration
                }
            }
        } catch (_: IOException) {
            // Preserve video resolution when YouTube's mobile configuration is temporarily unavailable.
        } finally {
            connection.disconnect()
        }
        return cachedMwebClientConfiguration ?: MwebClientConfiguration(
            clientVersion = clientVersion,
            playerScriptUrl = configuredPlayerScriptUrl,
            signatureTimestamp = signatureTimestamp,
        )
    }

    private fun playerClientStreamSummary(client: String, streaming: JSONObject): PlayerClientStreamSummary {
        var formatCount = 0
        var directUrlCount = 0
        var signatureCipherCount = 0
        var obfuscatedSignatureCount = 0
        var plainSignatureCount = 0
        var directUrlNCount = 0
        for (key in arrayOf("formats", "adaptiveFormats")) {
            val formats = streaming.optJSONArray(key) ?: continue
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                formatCount++
                val directUrl = format.optString("url")
                if (directUrl.isNotBlank()) {
                    directUrlCount++
                    if (runCatching { Uri.parse(directUrl).getQueryParameter("n").orEmpty().isNotBlank() }.getOrDefault(false)) {
                        directUrlNCount++
                    }
                }
                val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
                if (cipher.isNotBlank()) {
                    signatureCipherCount++
                    val fields = runCatching { Uri.parse("https://cipher.invalid/?$cipher") }.getOrNull()
                    if (!fields?.getQueryParameter("s").isNullOrBlank()) obfuscatedSignatureCount++
                    if (!fields?.getQueryParameter("sig").isNullOrBlank() ||
                        !fields?.getQueryParameter("signature").isNullOrBlank()
                    ) plainSignatureCount++
                }
            }
        }
        return PlayerClientStreamSummary(
            client = client,
            formatCount = formatCount,
            directUrlCount = directUrlCount,
            signatureCipherCount = signatureCipherCount,
            obfuscatedSignatureCount = obfuscatedSignatureCount,
            plainSignatureCount = plainSignatureCount,
            directUrlNCount = directUrlNCount,
            hasDashManifest = isTrustedStreamUrl(streaming.optString("dashManifestUrl")),
            hasHlsManifest = isTrustedStreamUrl(streaming.optString("hlsManifestUrl")),
            hasServerAbrStream = isTrustedStreamUrl(streaming.optString("serverAbrStreamingUrl")),
        )
    }

    private fun playerRequest(body: String, version: String, clientId: String, userAgent: String): JSONObject {
        val connection = URL("https://www.youtube.com/youtubei/v1/player?key=${urlEncode(apiKey)}&prettyPrint=false").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = PLAYER_TIMEOUT_MS
        connection.readTimeout = PLAYER_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Origin", "https://www.youtube.com")
        connection.setRequestProperty("Referer", "https://www.youtube.com/")
        connection.setRequestProperty("X-Youtube-Client-Name", clientId)
        connection.setRequestProperty("X-Youtube-Client-Version", version)
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("YouTube player request failed with HTTP $status.")
            val text = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("YouTube player response is larger than 8 MB.")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun configure(language: String, timeoutMs: Int = REQUEST_TIMEOUT_MS) {
        if (clientVersion.isNotEmpty() && System.currentTimeMillis() - configuredAt < TimeUnit.MINUTES.toMillis(15)) return
        val html = request("/?hl=${urlEncode(language)}", null, false, timeoutMs)
        val config = embeddedObject(html, "ytcfg.set(")
            ?: throw IOException("YouTube catalogue configuration is unavailable. Retry later.")
        val client = config.optJSONObject("INNERTUBE_CONTEXT")?.optJSONObject("client")
        clientVersion = config.optString("INNERTUBE_CLIENT_VERSION", client?.optString("clientVersion").orEmpty())
        apiKey = config.optString("INNERTUBE_API_KEY")
        signatureTimestamp = config.optInt("STS", 0)
        configuredPlayerScriptUrl = config.optString("PLAYER_JS_URL")
        visitorData = config.optString("VISITOR_DATA", client?.optString("visitorData").orEmpty())
        if (clientVersion.isBlank()) throw IOException("YouTube catalogue configuration is unavailable. Retry later.")
        configuredAt = System.currentTimeMillis()
    }

    private fun context(language: String): JSONObject {
        val client = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", clientVersion)
            .put("hl", language)
            .put("gl", "US")
            .put("timeZone", "UTC")
            .put("utcOffsetMinutes", 0)
        if (visitorData.isNotBlank()) client.put("visitorData", visitorData)
        return JSONObject().put("client", client)
    }

    private fun request(path: String, body: String?, api: Boolean, timeoutMs: Int = REQUEST_TIMEOUT_MS): String {
        throwIfCoolingDown()
        val connection = (URL("https://www.youtube.com$path").openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            instanceFollowRedirects = false
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", "https://www.youtube.com/")
            setRequestProperty("Origin", "https://www.youtube.com")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            if (api) {
                setRequestProperty("X-Youtube-Client-Name", "1")
                setRequestProperty("X-Youtube-Client-Version", clientVersion)
                if (visitorData.isNotBlank()) setRequestProperty("X-Goog-Visitor-Id", visitorData)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 429 || status == 503) updateCooldown(status, connection.getHeaderField("Retry-After"))
            if (status !in 200..299) throw IOException(httpError(status))
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("YouTube response is larger than 8 MB.")
                    output.write(buffer, 0, count)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSearchPage(root: JSONObject): SearchPage {
        val result = linkedMapOf<String, VideoItem>()
        var continuation: String? = null
        fun add(item: VideoItem?) {
            if (item != null) {
                val key = item.kind + ":" + item.id
                if (!result.containsKey(key)) result[key] = item
            }
        }
        fun visit(value: Any?, depth: Int) {
            if (depth > MAX_JSON_DEPTH) return
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), depth + 1)
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val renderer = value.optJSONObject(key)
                        when (key) {
                            "continuationItemRenderer" -> {
                                continuation = nestedString(renderer, "continuationEndpoint", "continuationCommand", "token")
                                    .ifBlank { nestedString(renderer, "button", "buttonRenderer", "command", "continuationCommand", "token") }
                            }
                            "continuationItemViewModel" -> {
                                continuation = nestedString(
                                    renderer,
                                    "continuationCommand",
                                    "innertubeCommand",
                                    "continuationCommand",
                                    "token",
                                ).ifBlank { nestedString(renderer, "continuationCommand", "token") }
                            }
                            "nextContinuationData" -> continuation = renderer?.optString("continuation")?.takeIf(String::isNotBlank)
                            "videoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "playlistPanelVideoRenderer", "reelItemRenderer" -> add(legacyVideo(renderer, key))
                            "playlistRenderer", "gridPlaylistRenderer" -> add(playlist(renderer))
                            "lockupViewModel" -> add(lockup(renderer))
                            "shortsLockupViewModel" -> add(shorts(renderer))
                            "menu", "navigationEndpoint", "header", "sidebar", "secondaryContents", "engagementPanels", "frameworkUpdates" -> Unit
                            else -> if (!key.contains("promoted", true) && !key.contains("adSlot", true)) visit(value.opt(key), depth + 1)
                        }
                    }
                }
            }
        }
        val contents = root.optJSONObject("contents")
        val primary = contents?.optJSONObject("twoColumnSearchResultsRenderer")?.optJSONObject("primaryContents")
        visit(primary ?: contents, 0)
        visit(root.optJSONObject("continuationContents"), 0)
        visit(root.optJSONArray("onResponseReceivedActions"), 0)
        if (result.isEmpty() && primary == null && contents == null) throw IOException("YouTube returned an unsupported search response.")
        return SearchPage(result.values.take(MAX_PAGE_ITEMS), continuation)
    }

    private fun legacyVideo(renderer: JSONObject?, rendererName: String): VideoItem? {
        renderer ?: return null
        val id = renderer.optString("videoId")
        if (!isVideoId(id) || renderer.optString("isPlayable") == "false") return null
        val title = text(renderer.opt("title"))
        if (title == "[Deleted video]" || title == "[Private video]") return null
        val byline = renderer.opt("ownerText") ?: renderer.opt("shortBylineText") ?: renderer.opt("longBylineText")
        val channel = text(byline)
        val channelId = channelId(byline)
        val date = text(renderer.opt("publishedTimeText"))
        val endpointUrl = nestedString(renderer, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url")
        var kind = if (rendererName == "reelItemRenderer" || endpointUrl.startsWith("/shorts/")) "shorts" else "video"
        val flags = renderer.opt("badges")?.toString().orEmpty() + renderer.opt("thumbnailOverlays")?.toString().orEmpty()
        val liveStatus = when {
            renderer.optJSONObject("upcomingEventData") != null -> "is_upcoming"
            flags.contains("BADGE_STYLE_TYPE_LIVE_NOW") || flags.contains("\"style\":\"LIVE\"") -> "is_live"
            date.contains("streamed", ignoreCase = true) -> "was_live"
            else -> ""
        }
        if (liveStatus.isNotEmpty()) kind = "live"
        val duration = text(renderer.opt("lengthText")).ifBlank { formatDuration(renderer.optString("lengthSeconds")) }
        return VideoItem(id, title, channel, channelId, date, duration, kind, liveStatus, thumbnail(renderer), "https://www.youtube.com/watch?v=$id")
    }

    private fun playlist(renderer: JSONObject?): VideoItem? {
        renderer ?: return null
        val id = renderer.optString("playlistId")
        if (!isPlaylistId(id)) return null
        return VideoItem(
            id = id,
            title = text(renderer.opt("title")),
            channel = text(renderer.opt("shortBylineText")),
            kind = "playlist",
            thumbnailUrl = thumbnail(renderer),
            url = "https://www.youtube.com/playlist?list=$id",
        )
    }

    private fun lockup(renderer: JSONObject?): VideoItem? {
        renderer ?: return null
        val id = renderer.optString("contentId")
        val type = renderer.optString("contentType")
        val isPlaylist = type.contains("PLAYLIST", true) || type.contains("PODCAST", true)
        if (isPlaylist && !isPlaylistId(id)) return null
        if (!isPlaylist && type.isNotBlank() && !type.contains("VIDEO", true)) return null
        if (!isPlaylist && !isVideoId(id)) return null
        val metadata = renderer.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val rows = metadata?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
        val parts = metadataParts(rows)
        val byline = parts.firstOrNull { it.second.isNotBlank() }
        val date = parts.firstOrNull { it.second.isBlank() }?.first.orEmpty()
        val kind = if (isPlaylist) "playlist" else if (date.contains("streamed", true)) "live" else "video"
        val url = if (isPlaylist) "https://www.youtube.com/playlist?list=$id" else "https://www.youtube.com/watch?v=$id"
        return VideoItem(
            id = id,
            title = text(metadata?.opt("title")),
            channel = byline?.first.orEmpty(),
            channelId = byline?.second.orEmpty(),
            published = date,
            kind = kind,
            liveStatus = if (kind == "live") "was_live" else "",
            thumbnailUrl = thumbnail(renderer),
            url = url,
        )
    }

    private fun shorts(renderer: JSONObject?): VideoItem? {
        renderer ?: return null
        val id = nestedString(renderer, "onTap", "innertubeCommand", "reelWatchEndpoint", "videoId")
        if (!isVideoId(id)) return null
        return VideoItem(
            id = id,
            title = text(renderer.optJSONObject("overlayMetadata")?.opt("primaryText")),
            kind = "shorts",
            thumbnailUrl = thumbnail(renderer),
            url = "https://www.youtube.com/shorts/$id",
        )
    }

    private fun metadataParts(rows: JSONArray?): List<Pair<String, String>> {
        if (rows == null) return emptyList()
        val output = ArrayList<Pair<String, String>>()
        for (index in 0 until rows.length()) {
            val parts = rows.optJSONObject(index)?.optJSONArray("metadataParts") ?: continue
            for (partIndex in 0 until parts.length()) {
                val value = parts.optJSONObject(partIndex)?.optJSONObject("text") ?: continue
                output += text(value) to channelIdFromRuns(value.optJSONArray("commandRuns"))
            }
        }
        return output
    }

    private fun thumbnail(value: JSONObject?): String {
        value ?: return ""
        val found = ArrayList<String>()
        fun visit(node: Any?, depth: Int) {
            if (depth > 12) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), depth + 1)
                is JSONObject -> {
                    val sources = node.optJSONArray("thumbnails") ?: node.optJSONArray("sources")
                    if (sources != null) for (i in 0 until sources.length()) {
                        val url = sources.optJSONObject(i)?.optString("url").orEmpty()
                        if (url.startsWith("https://")) found += url
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key !in setOf("navigationEndpoint", "menu", "header")) visit(node.opt(key), depth + 1)
                    }
                }
            }
        }
        visit(value.opt("thumbnail"), 0)
        if (found.isEmpty()) visit(value.opt("contentImage"), 0)
        return found.lastOrNull().orEmpty()
    }

    private fun text(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> {
            val simple = value.optString("simpleText", value.optString("content"))
            if (simple.isNotBlank()) simple else buildString {
                val runs = value.optJSONArray("runs") ?: return@buildString
                for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        else -> ""
    }

    private fun channelId(byline: Any?): String {
        if (byline !is JSONObject) return ""
        val runs = byline.optJSONArray("runs") ?: return ""
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val id = nestedString(run, "navigationEndpoint", "browseEndpoint", "browseId")
            if (id.startsWith("UC") && id.length == 24) return id
        }
        return ""
    }

    private fun channelIdFromRuns(runs: JSONArray?): String {
        if (runs == null) return ""
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val id = nestedString(run, "onTap", "innertubeCommand", "browseEndpoint", "browseId")
            if (id.startsWith("UC") && id.length == 24) return id
        }
        return ""
    }

    private fun loadVideoMetadata(id: String, language: String, kindHint: String): SearchPage {
        val html = request("/watch?v=${urlEncode(id)}&hl=${urlEncode(language)}", null, false)
        val player = embeddedObject(html, "ytInitialPlayerResponse =")
            ?: throw IOException("YouTube did not return usable video information.")
        val details = player.optJSONObject("videoDetails") ?: throw IOException("YouTube did not return usable video information.")
        if (details.optString("videoId") != id) throw IOException("YouTube did not return usable video information.")
        val micro = player.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        val live = micro?.optJSONObject("liveBroadcastDetails")
        val liveStatus = when {
            details.optBoolean("isLive") || live?.optBoolean("isLiveNow") == true -> "is_live"
            details.optBoolean("isUpcoming") -> "is_upcoming"
            live != null -> "was_live"
            else -> ""
        }
        val date = if (micro == null) "" else micro.optString("publishDate", micro.optString("uploadDate"))
        val item = VideoItem(
            id = id,
            title = details.optString("title"),
            channel = details.optString("author"),
            channelId = details.optString("channelId"),
            published = date,
            duration = formatDuration(details.optString("lengthSeconds")),
            kind = if (liveStatus.isNotEmpty()) "live" else kindHint,
            liveStatus = liveStatus,
            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            url = "https://www.youtube.com/watch?v=$id",
        )
        return SearchPage(listOf(item), null)
    }

    private data class VideoLink(val id: String, val kind: String)

    /** Creates a local-library entry from a supported public YouTube video URL without a network lookup. */
    fun videoFromUrl(input: String): VideoItem? {
        val raw = input.trim()
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https") || uri.encodedAuthority?.contains('@') == true) return null
        val host = uri.host?.lowercase().orEmpty()
        if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")) return null
        val expectedPort = if (scheme == "https") 443 else 80
        if (uri.port != -1 && uri.port != expectedPort) return null
        val link = extractVideoLink(raw) ?: return null
        val canonicalUrl = if (link.kind == "shorts") {
            "https://www.youtube.com/shorts/${link.id}"
        } else {
            "https://www.youtube.com/watch?v=${link.id}"
        }
        return VideoItem(id = link.id, title = canonicalUrl, kind = link.kind, url = canonicalUrl)
    }

    private fun extractVideoLink(input: String): VideoLink? {
        if (isVideoId(input)) return VideoLink(input, "video")
        val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (host != "youtube.com" && host != "www.youtube.com" && host != "m.youtube.com" && host != "youtu.be" && !host.endsWith(".youtube.com")) return null
        val link = when {
            host == "youtu.be" -> VideoLink(uri.lastPathSegment.orEmpty(), "video")
            uri.path == "/watch" -> VideoLink(uri.getQueryParameter("v").orEmpty(), "video")
            uri.pathSegments.size >= 2 && uri.pathSegments[0] in setOf("shorts", "live", "embed") -> VideoLink(
                uri.pathSegments[1],
                if (uri.pathSegments[0] == "shorts") "shorts" else "video",
            )
            else -> null
        }
        return link?.takeIf { isVideoId(it.id) }
    }

    private fun extractPlaylistId(input: String): String? {
        val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (host != "youtube.com" && host != "www.youtube.com" && host != "m.youtube.com" && !host.endsWith(".youtube.com")) return null
        if (uri.path != "/playlist") return null
        return uri.getQueryParameter("list")?.takeIf(::isPlaylistId)
    }

    private fun isChannelUrl(input: String): Boolean {
        val uri = runCatching { Uri.parse(input) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        if (host != "youtube.com" && host != "www.youtube.com" && host != "m.youtube.com" && !host.endsWith(".youtube.com")) return false
        val segments = uri.pathSegments
        return segments.firstOrNull() == "channel" || segments.firstOrNull() in setOf("c", "user") || segments.firstOrNull()?.startsWith("@") == true
    }

    private fun resolveChannelBrowse(url: String, language: String): JSONObject {
        val response = JSONObject(request(
            "/youtubei/v1/navigation/resolve_url?prettyPrint=false",
            JSONObject().put("context", context(language)).put("url", url).toString(),
            true,
        ))
        val browse = response.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")
            ?: throw IOException("YouTube could not resolve this channel URL.")
        val browseId = browse.optString("browseId")
        if (browseId.isBlank()) throw IOException("YouTube could not resolve this channel URL.")
        return JSONObject().put("browseId", browseId).also { resolved ->
            browse.optString("params").takeIf(String::isNotBlank)?.let { resolved.put("params", it) }
        }
    }

    private fun channelTitle(root: JSONObject): String {
        var title = ""
        fun visit(value: Any?, depth: Int) {
            if (depth > MAX_JSON_DEPTH || title.isNotBlank()) return
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), depth + 1)
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext() && title.isBlank()) {
                        val key = keys.next()
                        val child = value.optJSONObject(key)
                        when (key) {
                            "channelMetadataRenderer", "c4TabbedHeaderRenderer" -> title = text(child?.opt("title"))
                            else -> visit(value.opt(key), depth + 1)
                        }
                    }
                }
            }
        }
        visit(root, 0)
        return title.trim()
    }

    private fun embeddedObject(source: String, marker: String): JSONObject? {
        var offset = 0
        while (offset < source.length) {
            val markerAt = source.indexOf(marker, offset)
            if (markerAt < 0) return null
            offset = markerAt + marker.length
            var start = offset
            while (start < source.length && source[start].isWhitespace()) start++
            if (start >= source.length || source[start] != '{') continue
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until source.length) {
                val char = source[index]
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            runCatching { JSONObject(source.substring(start, index + 1)) }.getOrNull()?.let { return it }
                            offset = index + 1
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    private fun formatDuration(secondsText: String): String {
        val seconds = secondsText.toLongOrNull()?.takeIf { it in 1..863_999 } ?: return "—"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
    }

    private fun parseDurationMs(duration: String): Long {
        val parts = duration.split(':').mapNotNull(String::toLongOrNull)
        if (parts.isEmpty()) return 0
        var seconds = 0L
        for (part in parts) seconds = seconds * 60 + part
        return seconds * 1000
    }

    private fun nestedString(value: JSONObject?, vararg path: String): String {
        if (path.isEmpty()) return ""
        var node = value
        for (index in 0 until path.lastIndex) node = node?.optJSONObject(path[index]) ?: return ""
        return node?.optString(path.last()).orEmpty()
    }

    private fun isVideoId(value: String) = VIDEO_ID.matches(value)
    private fun isPlaylistId(value: String) = PLAYLIST_ID.matches(value)
    private fun urlEncode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun updateCooldown(status: Int, retryAfter: String?) {
        val fallback = if (status == 429) 60L else 30L
        val seconds = retryAfter?.toLongOrNull()?.coerceIn(1, MAX_COOLDOWN_SECONDS) ?: fallback
        cooldownUntil = maxOf(cooldownUntil, System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds))
    }

    private fun throwIfCoolingDown() {
        val remaining = ((cooldownUntil - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
        if (remaining > 0) throw IOException("YouTube paused catalogue requests. Retry in $remaining s.")
    }

    private fun httpError(status: Int): String = when (status) {
        401, 403 -> "YouTube refused the catalogue request (HTTP $status). Check consent or session."
        408 -> "The YouTube catalogue request timed out. Retry later."
        429 -> "YouTube paused catalogue requests. Retry in 60 s."
        in 500..599 -> "YouTube catalogue is temporarily unavailable (HTTP $status). Retry later."
        else -> "YouTube catalogue request failed (HTTP $status)."
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 25_000
        private const val PLAYER_TIMEOUT_MS = 12_000
        private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
        private val HANDLE = Regex("^@[^/?#\\s]{1,100}$")
        private val SUPPORTED_DOWNLOAD_MIME_TYPES = setOf("video/mp4", "video/webm", "audio/mp4", "audio/webm", "audio/mpeg")
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val MAX_PAGE_ITEMS = 100
        private const val MAX_JSON_DEPTH = 80
        private const val MAX_COOLDOWN_SECONDS = 86_400L
        private const val MAX_URL_RECOVERY_ATTEMPTS = 8
        private const val MAX_URL_RECOVERY_DEPENDENCIES = 8
        private const val MAX_URL_N_BATCH_SIZE = 64
        private const val MAX_MARKER_URL_CANDIDATES = 3
        private const val MAX_MARKER_URL_BATCH = 24
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val MWEB_USER_AGENT = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
        private const val MAX_MWEB_CONFIG_BYTES = 4 * 1024 * 1024
        private val MWEB_CLIENT_VERSION_PATTERN = Regex("""[\"']INNERTUBE_CLIENT_VERSION[\"']\s*:\s*[\"']([0-9.]{1,64})[\"']""")
        private val MWEB_PLAYER_SCRIPT_PATTERN = Regex("""[\"']PLAYER_JS_URL[\"']\s*:\s*[\"']([^\"']{1,256})[\"']""")
        private val MWEB_SIGNATURE_TIMESTAMP_PATTERN = Regex("""[\"']STS[\"']\s*:\s*(\d{5,6})""")
        private const val MAX_CIPHER_LENGTH = 64 * 1024
        private val SIGNATURE_PARAMETER = Regex("[A-Za-z0-9_]{1,32}")
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{1,256}$")
    }
}
