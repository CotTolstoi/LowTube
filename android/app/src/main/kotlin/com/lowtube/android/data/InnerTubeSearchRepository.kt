package com.lowtube.android.data

import android.os.Build
import android.os.SystemClock
import android.content.Context
import android.net.Uri
import com.lowtube.android.model.SearchPage
import com.lowtube.android.model.ChannelHeader
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
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
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
                var channelBrowseId = ""
                if (continuation.isNullOrBlank()) {
                    when {
                        playlistId != null -> body.put("browseId", "VL$playlistId")
                        channelUrl -> {
                            val browse = resolveChannelBrowse(query, language)
                            channelBrowseId = browse.optString("browseId")
                            body.put("browse", browse)
                        }
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
                val page = parseSearchPage(root)
                val header = page.channelHeader
                if (channelUrl && header != null && header.channelId.isBlank() && CHANNEL_ID.matches(channelBrowseId)) {
                    page.copy(channelHeader = header.copy(channelId = channelBrowseId))
                } else page
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
                val continuationToken = continuation
                    ?.removePrefix(RECOMMENDATION_TOPIC_CONTINUATION_PREFIX)
                    .orEmpty()
                topicRecommendations(language, topic, discoveryLanguage, continuationToken.ifBlank { null })
            }
        }

    private fun topicRecommendations(
        language: String,
        topic: String,
        discoveryLanguage: String,
        continuation: String?,
    ): SearchPage {
        val body = JSONObject().put("context", context(language))
        if (continuation.isNullOrBlank()) body.put("query", discoveryQuery(topic, discoveryLanguage))
        else body.put("continuation", continuation)
        val root = JSONObject(request("/youtubei/v1/search?prettyPrint=false", body.toString(), true))
        root.optJSONObject("responseContext")?.optString("visitorData")?.takeIf(String::isNotBlank)?.let { visitorData = it }
        val page = parseSearchPage(root)
        return page.copy(continuation = page.continuation?.let { RECOMMENDATION_TOPIC_CONTINUATION_PREFIX + it })
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
            val resolved = resolvePlayerData(video, preferWebClient) { streaming ->
                hasDirectOrDash(streaming) ||
                    (playerJavaScriptRuntime != null && hasObfuscatedSignaturePlaybackFormat(streaming))
            }
            resolveStreams(resolved.video, resolved.streaming, preferences, resolved.userAgent)
                .copy(youtubeCaptionTracks = resolved.captionTracks)
        }
    }

    private suspend fun resolvePlayerData(
        video: VideoItem,
        preferWebClient: Boolean,
        allowVrFallback: Boolean = false,
        acceptsStreaming: (JSONObject) -> Boolean,
    ): ResolvedPlayerData {
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
            allowVrFallback,
            acceptsStreaming,
        )
            .takeIf { acceptsStreaming(it.streaming) }
            ?: pageStreaming?.takeIf(acceptsStreaming)?.let { ResolvedPlayerResponse(it, USER_AGENT) }
            ?: throw IOException("YouTube did not return a stream suitable for this operation.")
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
        return ResolvedPlayerData(
            enrichedVideo,
            streaming,
            resolvedStreaming.userAgent,
            resolvedStreaming.captionTracks.ifEmpty { YouTubeCaptionTrackParser.parse(player, video.id) },
        )
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
            // Prefer the Android client because it usually supplies direct CDN URLs and
            // avoids a player-JS signature challenge. Keep WEB/IOS/MWEB/VR as fallbacks.
            val resolved = resolvePlayerData(video, preferWebClient = false, allowVrFallback = true) { streaming ->
                hasDownloadableFormat(streaming, preferences, audioOnly) ||
                    (playerJavaScriptRuntime != null && hasResolvableSignatureDownloadFormat(streaming, preferences, audioOnly))
            }
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
        val selected = if (audioOnly) {
            downloadableFormats(streaming, preferences, true).map { it to null }
        } else {
            val muxed = downloadableFormats(streaming, preferences, false).map { it to null }
            val separate = separateDownloadTracks(formats, preferences)
            muxed + separate
        }
        return selected.distinctBy { (videoFormat, audioFormat) ->
            "${videoFormat.json.optInt("itag", 0)}|${videoFormat.mimeType}|$audioOnly|${audioFormat?.json?.optInt("itag", 0) ?: 0}"
        }.sortedWith(
            if (audioOnly) compareByDescending<Pair<PlaybackFormat, PlaybackFormat?>> {
                it.first.json.optLong("bitrate", 0)
            }.thenByDescending { it.first.mimeType == "audio/mp4" }
            else compareByDescending<Pair<PlaybackFormat, PlaybackFormat?>> { it.first.json.optInt("height", 0) }
                .thenByDescending { it.first.json.optDouble("fps", 0.0) }
                .thenByDescending { it.first.json.optLong("bitrate", 0) },
        ).map { (format, audioFormat) ->
            val stream = format.toStream(video, isAudioOnly = audioOnly, userAgent = userAgent)
            val audioStream = audioFormat?.toStream(video, isAudioOnly = true, userAgent = userAgent)
            val key = if (audioStream == null) "${stream.itag}|${stream.mimeType}|$audioOnly"
                else "${stream.itag}|${stream.mimeType}|false|${audioStream.itag}|${audioStream.mimeType}"
            DownloadFormatOption(
                key = key,
                stream = stream,
                bitrateKbps = (format.json.optLong("bitrate", 0) / 1000L).toInt().coerceAtLeast(0),
                container = when (format.mimeType) {
                    "video/webm", "audio/webm" -> "WebM"
                    "audio/mpeg" -> "MP3"
                    else -> if (audioOnly) "M4A" else "MP4"
                },
                audioStream = audioStream,
            )
        }.let { sourceOptions ->
            sourceOptions + sourceOptions.filter(::supportsMatroskaOutput).map { option ->
                option.copy(key = "${option.key}|container=mkv", container = "MKV")
            }
        }
    }

    private fun supportsMatroskaOutput(option: DownloadFormatOption): Boolean {
        val videoCodec = option.stream.videoCodec.lowercase()
        val audioCodec = (option.audioStream?.audioCodec ?: option.stream.audioCodec).lowercase()
        return when {
            videoCodec.startsWith("avc1") && audioCodec.startsWith("mp4a.40.2") -> true
            videoCodec.startsWith("vp8") && audioCodec.startsWith("vorbis") -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && videoCodec.startsWith("vp9") &&
                (audioCodec.startsWith("vorbis") || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audioCodec.startsWith("opus"))) -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && videoCodec.startsWith("vp8") && audioCodec.startsWith("opus") -> true
            else -> false
        }
    }

    private fun separateMp4Tracks(
        formats: List<PlaybackFormat>,
        preferences: DownloadStreamPreferences,
    ): List<Pair<PlaybackFormat, PlaybackFormat?>> {
        val audio = formats.asSequence()
            .filter { it.audioOnly && it.mimeType == "audio/mp4" && it.codec.startsWith("mp4a") }
            .maxByOrNull { it.json.optLong("bitrate", 0) }
            ?: return emptyList()
        return formats.asSequence()
            .filter { it.videoOnly && it.mimeType == "video/mp4" && it.codec.startsWith("avc1") }
            .filter { it.json.optInt("height", 0) in 1..preferences.maxHeight }
            .filter { !preferences.h264Only || it.codec.startsWith("avc1") }
            .filter { !preferences.fps30Only || it.json.optDouble("fps", 0.0) <= 30.0 }
            .map { it to audio }
            .toList()
    }

    /** Select WebM codecs accepted by this Android release's platform MediaMuxer. */
    private fun separateWebmTracks(
        formats: List<PlaybackFormat>,
        preferences: DownloadStreamPreferences,
    ): List<Pair<PlaybackFormat, PlaybackFormat?>> {
        if (preferences.h264Only) return emptyList()
        val audioCodecs = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add("opus")
            add("vorbis")
        }
        val videoTracks = formats.asSequence()
            .filter { it.videoOnly && it.mimeType == "video/webm" }
            .filter { format -> isMuxableWebmVideoCodec(format.codec) }
            .filter { it.json.optInt("height", 0) in 1..preferences.maxHeight }
            .filter { !preferences.fps30Only || it.json.optDouble("fps", 0.0) <= 30.0 }
            .toList()
        if (videoTracks.isEmpty()) return emptyList()
        val audioTrack = formats.asSequence()
            .filter { it.audioOnly && it.mimeType == "audio/webm" }
            .filter { format -> audioCodecs.any { format.codec.startsWith(it, ignoreCase = true) } }
            .sortedWith(compareBy<PlaybackFormat> { format ->
                audioCodecs.indexOfFirst { format.codec.startsWith(it, ignoreCase = true) }
            }.thenByDescending { it.json.optLong("bitrate", 0) })
            .firstOrNull() ?: return emptyList()
        return videoTracks.map { it to audioTrack }
    }

    private fun separateDownloadTracks(
        formats: List<PlaybackFormat>,
        preferences: DownloadStreamPreferences,
    ) = separateMp4Tracks(formats, preferences) + separateWebmTracks(formats, preferences)

    private fun isMuxableWebmVideoCodec(codec: String): Boolean =
        codec.startsWith("vp8", ignoreCase = true) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                (codec.startsWith("vp9", ignoreCase = true) || codec.startsWith("vp09", ignoreCase = true)))

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
                val codecs = json.optString("mimeType").substringAfter("codecs=\"", "").substringBefore('"')
                    .split(',').map(String::trim).filter(String::isNotEmpty)
                val codec = codecs.firstOrNull().orEmpty()
                val audioCodec = codecs.firstOrNull { it.startsWith("mp4a", true) || it.startsWith("opus", true) || it.startsWith("vorbis", true) }.orEmpty()
                val videoOnly = mime.startsWith("video/") && !json.has("audioQuality")
                val audioOnly = mime.startsWith("audio/")
                val videoMuxed = mime.startsWith("video/") && !videoOnly
                formats += PlaybackFormat(json, url, mime, codec, audioCodec, videoOnly, audioOnly, videoMuxed)
            }
        }
        return formats
    }

    private fun hasDownloadableFormat(
        streaming: JSONObject,
        preferences: DownloadStreamPreferences,
        audioOnly: Boolean,
    ): Boolean = downloadableFormats(streaming, preferences, audioOnly).isNotEmpty() ||
        (!audioOnly && separateDownloadTracks(parsePlaybackFormats(streaming), preferences).isNotEmpty())

    private fun hasResolvableSignatureDownloadFormat(
        streaming: JSONObject,
        preferences: DownloadStreamPreferences,
        audioOnly: Boolean,
    ): Boolean {
        var hasMp4VideoTrack = false
        var hasMp4AudioTrack = false
        var hasWebmVideoTrack = false
        var hasWebmAudioTrack = false
        for (key in arrayOf("formats", "adaptiveFormats")) {
            val formats = streaming.optJSONArray(key) ?: continue
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val mime = format.optString("mimeType").substringBefore(';')
                if (audioOnly) {
                    if (mime !in SUPPORTED_DOWNLOAD_MIME_TYPES || !mime.startsWith("audio/")) continue
                } else {
                    if (mime == "audio/mp4" || mime == "audio/webm") {
                        val codec = format.optString("mimeType").substringAfter("codecs=\"", "").substringBefore('"')
                        if (hasDirectOrSignatureCipher(format)) {
                            if (mime == "audio/mp4" && codec.startsWith("mp4a")) hasMp4AudioTrack = true
                            if (mime == "audio/webm" && (codec.startsWith("vorbis", true) ||
                                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codec.startsWith("opus", true)))) {
                                hasWebmAudioTrack = true
                            }
                        }
                        continue
                    }
                    if (!mime.startsWith("video/") || format.optInt("height", 0) !in 1..preferences.maxHeight) continue
                    val codec = format.optString("mimeType").substringAfter("codecs=\"", "").substringBefore('"').substringBefore(',')
                    if (preferences.h264Only && !codec.startsWith("avc1")) continue
                    if (preferences.fps30Only && format.optDouble("fps", 0.0) > 30.0) continue
                    if (hasDirectOrSignatureCipher(format)) {
                        if (format.has("audioQuality")) return true
                        if (mime == "video/mp4" && codec.startsWith("avc1")) hasMp4VideoTrack = true
                        if (!preferences.h264Only && mime == "video/webm" && isMuxableWebmVideoCodec(codec)) {
                            hasWebmVideoTrack = true
                        }
                    }
                    continue
                }
                if (hasDirectOrSignatureCipher(format)) return true
            }
        }
        return !audioOnly && ((hasMp4VideoTrack && hasMp4AudioTrack) || (hasWebmVideoTrack && hasWebmAudioTrack))
    }

    private fun hasDirectOrSignatureCipher(format: JSONObject): Boolean {
        if (isTrustedStreamUrl(format.optString("url"))) return true
        val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
        if (cipher.isBlank() || cipher.length > MAX_CIPHER_LENGTH) return false
        val fields = runCatching { Uri.parse("https://cipher.invalid/?$cipher") }.getOrNull() ?: return false
        return fields.getQueryParameter("s").orEmpty().isNotBlank() && isTrustedStreamUrl(fields.getQueryParameter("url").orEmpty())
    }

    private fun downloadableFormats(
        streaming: JSONObject,
        preferences: DownloadStreamPreferences,
        audioOnly: Boolean,
    ): List<PlaybackFormat> = parsePlaybackFormats(streaming).filter { format ->
        (if (audioOnly) format.audioOnly else format.videoMuxed) &&
            format.mimeType in SUPPORTED_DOWNLOAD_MIME_TYPES &&
            (audioOnly || format.json.optInt("height", 0) in 1..preferences.maxHeight) &&
            (audioOnly || !preferences.h264Only || format.codec.startsWith("avc1")) &&
            (audioOnly || !preferences.fps30Only || format.json.optDouble("fps", 0.0) <= 30.0)
    }

    private data class ResolvedPlayerData(
        val video: VideoItem,
        val streaming: JSONObject,
        val userAgent: String,
        val captionTracks: List<com.lowtube.android.model.YouTubeCaptionTrack>,
    )
    private data class ResolvedPlayerResponse(
        val streaming: JSONObject,
        val userAgent: String,
        val playerScriptUrl: String? = null,
        val captionTracks: List<com.lowtube.android.model.YouTubeCaptionTrack> = emptyList(),
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
        val clientFields: JSONObject = JSONObject(),
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
        val audioCodec: String,
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
            audioCodec = if (isAudioOnly) codec else if (videoMuxed) audioCodec else "",
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

    private fun hasObfuscatedSignaturePlaybackFormat(streaming: JSONObject): Boolean {
        for (key in arrayOf("formats", "adaptiveFormats")) {
            val formats = streaming.optJSONArray(key) ?: continue
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                if (!format.optString("mimeType").startsWith("video/")) continue
                val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
                if (cipher.isBlank() || cipher.length > MAX_CIPHER_LENGTH) continue
                val fields = Uri.parse("https://cipher.invalid/?$cipher")
                val signature = fields.getQueryParameter("s").orEmpty()
                val url = fields.getQueryParameter("url").orEmpty()
                if (signature.isNotBlank() && isTrustedStreamUrl(url)) return true
            }
        }
        return false
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
        val pendingSignatureTransforms = mutableListOf<PendingSignatureTransform>()
        val pendingUrlTransforms = mutableListOf<PendingUrlTransform>()
        val pendingPathNTransforms = mutableListOf<PendingUrlTransform>()
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
                            if (executableFunctions.markerCandidateNames.isNotEmpty()) {
                                pendingSignatureTransforms.add(PendingSignatureTransform(format, cipher, obfuscated))
                            } else {
                                format.remove("signatureCipher")
                                format.remove("cipher")
                            }
                            continue
                        }
                        val signatureSource = executableFunctions.signatureProgram
                        if (signatureSource.isNullOrBlank()) {
                            if (executableFunctions.markerCandidateNames.isNotEmpty()) {
                                pendingSignatureTransforms.add(PendingSignatureTransform(format, cipher, obfuscated))
                            } else {
                                format.remove("signatureCipher")
                                format.remove("cipher")
                            }
                            continue
                        }
                        val signature = runCatching {
                            runtime.executeFunction(signatureSource, function, obfuscated, functions.signatureArgumentPrefix)
                        }.getOrNull()
                        val signedUrl = signature?.let { YoutubePlayerUrlTransformer.applySignatureCipher(cipher, it) }
                        if (signedUrl.isNullOrBlank() || !isTrustedStreamUrl(signedUrl)) {
                            if (executableFunctions.markerCandidateNames.isNotEmpty()) {
                                pendingSignatureTransforms.add(PendingSignatureTransform(format, cipher, obfuscated))
                            } else {
                                format.remove("signatureCipher")
                                format.remove("cipher")
                            }
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
                    val pending = PendingUrlTransform(format, directUrl)
                    if (activeExecutableFunctions.functions.urlPathThrottling != null && hasNPathChallenge(directUrl)) {
                        pendingPathNTransforms.add(pending)
                    } else {
                        pendingUrlTransforms.add(pending)
                    }
                }
            }
        }
        val signatureCandidates = executableFunctions.markerCandidateNames.take(MAX_MARKER_URL_CANDIDATES)
        if (pendingSignatureTransforms.isNotEmpty() && signatureCandidates.isNotEmpty()) {
            pendingSignatureTransforms.chunked(MAX_MARKER_URL_BATCH).forEach { batch ->
                val results = try {
                    runtime.executeFullPlayerUrlObjectSignatureTransforms(
                        playerScript.source,
                        signatureCandidates,
                        List(batch.size) { SIGNATURE_PROBE_URL },
                        batch.map { it.signature },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                batch.forEachIndexed { index, item ->
                    val result = results.getOrNull(index)
                    val signature = result?.signature?.takeIf {
                        result.signatureChanged && it.isNotBlank() && it.length <= MAX_CIPHER_LENGTH && it.none(Char::isISOControl)
                    }
                    val signedUrl = signature?.let { YoutubePlayerUrlTransformer.applySignatureCipher(item.cipher, it) }
                    if (signedUrl.isNullOrBlank() || !isTrustedStreamUrl(signedUrl)) {
                        item.format.remove("signatureCipher")
                        item.format.remove("cipher")
                    } else {
                        item.format.put("url", signedUrl)
                        item.format.remove("signatureCipher")
                        item.format.remove("cipher")
                        val throttled = runCatching { Uri.parse(signedUrl).getQueryParameter("n").orEmpty() }.getOrNull().orEmpty()
                        if (throttled.isNotBlank()) pendingUrlTransforms.add(PendingUrlTransform(item.format, signedUrl))
                    }
                }
            }
        }
        val pathFunction = activeExecutableFunctions.functions.urlPathThrottling
        if (pathFunction != null && pendingPathNTransforms.isNotEmpty()) {
            pendingPathNTransforms.chunked(MAX_URL_N_BATCH_SIZE).forEach { batch ->
                val outputs = try {
                    runtime.executeFullPlayerFunctions(
                        playerScript.source,
                        batch.map { item -> YoutubePlayerJavaScriptRuntime.FunctionCall(pathFunction, item.url) },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                batch.forEachIndexed { index, item ->
                    val accepted = outputs.getOrNull(index)?.let {
                        YoutubePlayerUrlTransformer.acceptUrlNTransform(item.url, it)
                    }
                    if (accepted != null && isTrustedStreamUrl(accepted)) item.format.put("url", accepted)
                    else pendingUrlTransforms.add(item)
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
    private data class PendingSignatureTransform(val format: JSONObject, val cipher: String, val signature: String)

    private fun hasNPathChallenge(url: String): Boolean {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return false
        val segments = path.split('/')
        return (0 until segments.lastIndex).any { index -> segments[index] == "n" && segments[index + 1].isNotBlank() }
    }

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
        allowVrFallback: Boolean,
        acceptsStreaming: (JSONObject) -> Boolean,
    ): ResolvedPlayerResponse {
        if (apiKey.isBlank()) throw IOException("YouTube player configuration is incomplete. Retry the video.")
        val androidClient = PlayerRequestClient("ANDROID", "20.13.41", "3", USER_AGENT)
        val webClient = PlayerRequestClient("WEB", clientVersion, "1", USER_AGENT)
        val iosClient = PlayerRequestClient("IOS", IOS_CLIENT_VERSION, "5", IOS_USER_AGENT)
        val baseClients = if (preferWebClient) {
            listOf(webClient, androidClient, iosClient)
        } else {
            listOf(androidClient, webClient, iosClient)
        }
        val clients = if (allowVrFallback) {
            baseClients + PlayerRequestClient(
                name = "ANDROID_VR",
                version = ANDROID_VR_CLIENT_VERSION,
                id = "28",
                userAgent = ANDROID_VR_USER_AGENT,
                clientFields = JSONObject()
                    .put("deviceMake", "Oculus")
                    .put("deviceModel", "Quest 3")
                    .put("androidSdkVersion", 32)
                    .put("osName", "Android")
                    .put("osVersion", "12L")
                    .put("userAgent", ANDROID_VR_USER_AGENT),
            )
        } else baseClients
        var lastStatus = ""
        for (playerClient in clients) {
            val name = playerClient.name
            val version = playerClient.version
            val clientId = playerClient.id
            val userAgent = playerClient.userAgent
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
            if (name == "IOS") {
                client.put("deviceMake", "Apple")
                    .put("deviceModel", "iPhone16,2")
                    .put("osName", "iPhone")
                    .put("osVersion", "18.3.2.22D82")
                    .put("userAgent", userAgent)
            }
            val extraKeys = playerClient.clientFields.keys()
            while (extraKeys.hasNext()) {
                val key = extraKeys.next()
                client.put(key, playerClient.clientFields.opt(key))
            }
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
                if (streaming != null && acceptsStreaming(streaming)) return ResolvedPlayerResponse(
                    streaming = streaming,
                    userAgent = userAgent,
                    captionTracks = YouTubeCaptionTrackParser.parse(response, videoId),
                )
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
                if (acceptsStreaming(streaming)) {
                    return ResolvedPlayerResponse(
                        streaming = streaming,
                        userAgent = mwebClient.userAgent,
                        playerScriptUrl = mwebConfiguration.playerScriptUrl,
                        captionTracks = YouTubeCaptionTrackParser.parse(response, videoId),
                    )
                }
            }
            lastStatus = response.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        } catch (_: IOException) {
            // Fall through to watch-page data or the bounded unavailable-stream message.
        }
        val streaming = pageResponse.optJSONObject("streamingData")
        if (streaming != null && acceptsStreaming(streaming)) return ResolvedPlayerResponse(
            streaming = streaming,
            userAgent = USER_AGENT,
            captionTracks = YouTubeCaptionTrackParser.parse(pageResponse, videoId),
        )
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

    internal fun parseSearchPage(root: JSONObject): SearchPage {
        val result = linkedMapOf<String, VideoItem>()
        var continuation: String? = null
        var playlistTitle: String? = null
        var channelHeader: ChannelHeader? = null
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
                            "channelMetadataRenderer", "c4TabbedHeaderRenderer", "pageHeaderRenderer" -> {
                                val parsed = parseChannelHeader(key, renderer)
                                if (parsed != null && channelHeader?.title.isNullOrBlank()) channelHeader = parsed
                                visit(renderer, depth + 1)
                            }
                            "pageHeaderViewModel" -> visit(renderer, depth + 1)
                            "playlistHeaderRenderer", "playlistMetadataRenderer" -> {
                                val title = text(renderer?.opt("title")).takeIf(String::isNotBlank)
                                if (!title.isNullOrBlank()) playlistTitle = title
                                visit(renderer, depth + 1)
                            }
                            "header" -> visit(value.opt(key), depth + 1)
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
                            "videoRenderer", "gridVideoRenderer", "reelItemRenderer" -> add(legacyVideo(renderer, key))
                            "playlistVideoRenderer", "playlistPanelVideoRenderer" -> add(legacyVideo(renderer, key, preserveUnavailable = true))
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
        visit(root.optJSONObject("header"), 0)
        visit(root.optJSONObject("continuationContents"), 0)
        visit(root.optJSONArray("onResponseReceivedActions"), 0)
        visit(root.opt("onResponseReceivedCommands"), 0)
        if (result.isEmpty() && primary == null && contents == null) {
            val responseFields = root.keys().asSequence().take(MAX_DIAGNOSTIC_RESPONSE_FIELDS).joinToString(", ")
                .ifBlank { "none" }
            throw IOException("YouTube returned an unsupported search response (top-level fields: $responseFields).")
        }
        return SearchPage(result.values.take(MAX_PAGE_ITEMS), continuation, playlistTitle, channelHeader)
    }

    private fun parseChannelHeader(rendererName: String, renderer: JSONObject?): ChannelHeader? {
        renderer ?: return null
        val viewModel = if (rendererName == "pageHeaderRenderer") {
            renderer.optJSONObject("content")?.optJSONObject("pageHeaderViewModel") ?: return null
        } else renderer
        val titleNode = viewModel.optJSONObject("title")
        val title = text(viewModel.opt("title"))
            .ifBlank { text(titleNode?.optJSONObject("dynamicTextViewModel")?.opt("text")) }
            .trim().take(250)
        if (title.isBlank()) return null
        val channelId = renderer.optString("externalId")
            .ifBlank { renderer.optString("channelId") }
            .ifBlank { channelIdIn(renderer) }
            .takeIf { it.startsWith("UC") && it.length == 24 }
            .orEmpty()
        val avatar = channelImageUrl(
            viewModel.opt("avatar")
                ?: viewModel.opt("channelThumbnail")
                ?: viewModel.opt("image"),
        )
        val banner = if (rendererName == "c4TabbedHeaderRenderer" || rendererName == "pageHeaderRenderer") {
            channelImageUrl(viewModel.opt("banner"))
        } else ""
        val subscriberCount = text(viewModel.opt("subscriberCountText"))
            .ifBlank { text(viewModel.opt("subscriberCount")) }
            .ifBlank { channelSubscriberCount(viewModel.optJSONObject("metadata")) }
            .take(120)
        val descriptionNode = viewModel.optJSONObject("description")
        val description = text(viewModel.opt("description"))
            .ifBlank { text(descriptionNode?.optJSONObject("descriptionPreviewViewModel")?.opt("description")) }
            .take(500)
        return ChannelHeader(channelId, title, avatar, banner, subscriberCount, description)
    }

    private fun channelIdIn(root: JSONObject): String {
        var channelId = ""
        fun visit(node: Any?, depth: Int) {
            if (depth > 12 || channelId.isNotBlank()) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), depth + 1)
                is JSONObject -> {
                    val candidate = node.optString("browseId")
                    if (candidate.startsWith("UC") && candidate.length == 24) {
                        channelId = candidate
                    } else {
                        val keys = node.keys()
                        while (keys.hasNext() && channelId.isBlank()) visit(node.opt(keys.next()), depth + 1)
                    }
                }
            }
        }
        visit(root, 0)
        return channelId
    }

    private fun channelSubscriberCount(metadata: JSONObject?): String {
        val rows = metadata?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows") ?: return ""
        for (rowIndex in 0 until rows.length()) {
            val parts = rows.optJSONObject(rowIndex)?.optJSONArray("metadataParts") ?: continue
            for (partIndex in 0 until parts.length()) {
                val part = parts.optJSONObject(partIndex) ?: continue
                val value = text(part.opt("text")).take(120)
                val marker = part.optString("accessibilityLabel") + " " + value
                if (marker.contains("subscriber", ignoreCase = true) ||
                    marker.contains("підпис", ignoreCase = true) ||
                    marker.contains("подпис", ignoreCase = true) ||
                    marker.contains("abonn", ignoreCase = true) ||
                    marker.contains("suscrip", ignoreCase = true)
                ) return value
            }
        }
        return ""
    }

    private fun channelImageUrl(value: Any?): String {
        val urls = ArrayList<String>()
        fun visit(node: Any?, depth: Int) {
            if (depth > 8 || urls.size >= 32) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), depth + 1)
                is JSONObject -> {
                    val direct = node.optString("url")
                    if (direct.isNotBlank()) urls += direct
                    val keys = node.keys()
                    while (keys.hasNext()) visit(node.opt(keys.next()), depth + 1)
                }
            }
        }
        visit(value, 0)
        return urls.lastOrNull { candidate ->
            candidate.length <= 2_000 && runCatching {
                val uri = Uri.parse(candidate)
                uri.scheme == "https" && uri.host?.lowercase() in setOf("yt3.ggpht.com", "yt3.googleusercontent.com")
            }.getOrDefault(false)
        }.orEmpty()
    }

    private fun legacyVideo(renderer: JSONObject?, rendererName: String, preserveUnavailable: Boolean = false): VideoItem? {
        renderer ?: return null
        val id = renderer.optString("videoId")
        if (!isVideoId(id)) return null
        val title = text(renderer.opt("title"))
        val unavailable = renderer.optString("isPlayable") == "false" || title == "[Deleted video]" || title == "[Private video]"
        if (unavailable && !preserveUnavailable) return null
        val byline = renderer.opt("ownerText") ?: renderer.opt("shortBylineText") ?: renderer.opt("longBylineText")
        val channel = text(byline)
        val channelId = channelId(byline)
        val date = text(renderer.opt("publishedTimeText"))
        val endpointUrl = nestedString(renderer, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url")
        var kind = when {
            unavailable -> "unavailable"
            rendererName == "reelItemRenderer" || endpointUrl.startsWith("/shorts/") -> "shorts"
            else -> "video"
        }
        val flags = renderer.opt("badges")?.toString().orEmpty() + renderer.opt("thumbnailOverlays")?.toString().orEmpty()
        val liveStatus = when {
            renderer.optJSONObject("upcomingEventData") != null -> "is_upcoming"
            flags.contains("BADGE_STYLE_TYPE_LIVE_NOW") || flags.contains("\"style\":\"LIVE\"") -> "is_live"
            date.contains("streamed", ignoreCase = true) -> "was_live"
            else -> ""
        }
        if (liveStatus.isNotEmpty() && !unavailable) kind = "live"
        val duration = text(renderer.opt("lengthText")).ifBlank { formatDuration(renderer.optString("lengthSeconds")) }
        return VideoItem(
            id, title, channel, channelId, date, duration, kind, liveStatus,
            if (unavailable) "" else thumbnail(renderer), "https://www.youtube.com/watch?v=$id",
            channelAvatarUrl = channelAvatar(renderer),
        )
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
            channelAvatarUrl = channelAvatar(renderer),
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
            channelAvatarUrl = channelAvatar(renderer),
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

    private fun channelAvatar(renderer: JSONObject?): String {
        val thumbnail = renderer
            ?.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")
            ?.optJSONObject("thumbnail")
            ?: return ""
        val candidate = thumbnail.optJSONArray("thumbnails")
            ?.let { thumbnails ->
                (0 until thumbnails.length()).mapNotNull { thumbnails.optJSONObject(it)?.optString("url") }
                    .lastOrNull(String::isNotBlank)
            }
            ?: return ""
        val uri = Uri.parse(candidate)
        val host = uri.host?.lowercase().orEmpty()
        return candidate.takeIf {
            uri.scheme == "https" && host in setOf("yt3.ggpht.com", "yt3.googleusercontent.com") && candidate.length <= 2_000
        }.orEmpty()
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
        val seconds = retryAfterSeconds(retryAfter, fallback, MAX_COOLDOWN_SECONDS)
        val retryAt = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(seconds)
        synchronized(catalogueCooldown) {
            catalogueCooldown.set(maxOf(catalogueCooldown.get(), retryAt))
        }
    }

    private fun throwIfCoolingDown() {
        val remaining = ((catalogueCooldown.get() - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
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
        private val catalogueCooldown = java.util.concurrent.atomic.AtomicLong()
        private const val REQUEST_TIMEOUT_MS = 25_000
        private const val PLAYER_TIMEOUT_MS = 12_000
        private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
        private val HANDLE = Regex("^@[^/?#\\s]{1,100}$")
        private val SUPPORTED_DOWNLOAD_MIME_TYPES = setOf("video/mp4", "video/webm", "audio/mp4", "audio/webm", "audio/mpeg")
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val MAX_PAGE_ITEMS = 100
        private const val MAX_JSON_DEPTH = 80
        private const val RECOMMENDATION_TOPIC_CONTINUATION_PREFIX = "lowtube:topic:"
        private const val MAX_DIAGNOSTIC_RESPONSE_FIELDS = 8
        private const val MAX_COOLDOWN_SECONDS = 86_400L
        private const val MAX_URL_RECOVERY_ATTEMPTS = 8
        private const val MAX_URL_RECOVERY_DEPENDENCIES = 8
        private const val MAX_URL_N_BATCH_SIZE = 64
        private const val MAX_MARKER_URL_CANDIDATES = 3
        private const val MAX_MARKER_URL_BATCH = 24
        private const val SIGNATURE_PROBE_URL = "https://youtube.com/watch?v=lowtube-signature"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val MWEB_USER_AGENT = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
        private const val IOS_CLIENT_VERSION = "21.26.4"
        private const val IOS_USER_AGENT = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        private const val ANDROID_VR_CLIENT_VERSION = "1.65.10"
        private const val ANDROID_VR_USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; " +
            "eureka-user Build/SQ3A.220605.009.A1) gzip"
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
