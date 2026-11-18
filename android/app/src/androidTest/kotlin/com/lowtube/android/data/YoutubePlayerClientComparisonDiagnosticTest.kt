package com.lowtube.android.data

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Opt-in metadata-only comparison of anonymous Android and WEB player clients; it never creates a media player. */
@RunWith(AndroidJUnit4::class)
class YoutubePlayerClientComparisonDiagnosticTest {
    @Test
    fun reportsAggregateFormatsFromAndroidAndWebClients() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e runPlayerClientComparison true to run this bounded network diagnostic",
            arguments.getString("runPlayerClientComparison") == "true",
        )
        val wallStarted = SystemClock.elapsedRealtime()
        val cpuStarted = Process.getElapsedCpuTime()
        val pssStarted = Debug.getPss()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = InnerTubeSearchRepository(context)
        val summaries = mutableListOf<PlayerClientStreamSummary>()
        repository.playerClientStreamSummaryObserver = { summaries.add(it) }
        val videoIds = arguments.getString("playerComparisonVideoIds").orEmpty()
            .take(256)
            .split(',')
            .map(String::trim)
            .filter { VIDEO_ID.matches(it) }
            .distinct()
            .take(MAX_VIDEO_IDS)
            .ifEmpty { listOf(DEFAULT_VIDEO_ID) }

        videoIds.forEach { videoId ->
            val video = VideoItem(
                id = videoId,
                title = "Public diagnostic fixture",
                url = "https://www.youtube.com/watch?v=$videoId",
            )
            listOf(false, true).forEach { preferWebClient ->
                val before = summaries.size
                val resolution = runCatching { repository.resolvePlayback(video, preferWebClient = preferWebClient) }
                val attempts = summaries.drop(before)
                attempts.forEach { summary ->
                    System.out.println(
                        "playerClientAggregate; video=$videoId; client=${summary.client}; formats=${summary.formatCount}; " +
                            "direct=${summary.directUrlCount}; signatureCipher=${summary.signatureCipherCount}; " +
                            "obfuscatedS=${summary.obfuscatedSignatureCount}; plainSig=${summary.plainSignatureCount}; " +
                            "directN=${summary.directUrlNCount}; dash=${summary.hasDashManifest}; " +
                            "hls=${summary.hasHlsManifest}; serverAbr=${summary.hasServerAbrStream}",
                    )
                }
                val failureReason = resolution.exceptionOrNull()?.message.orEmpty()
                    .replace(Regex("[^A-Za-z0-9_ -]"), " ")
                    .take(120)
                System.out.println(
                    "playerClientResolution; video=$videoId; preferred=${if (preferWebClient) "WEB" else "ANDROID"}; " +
                        "completed=${resolution.isSuccess}; failureType=${resolution.exceptionOrNull()?.javaClass?.simpleName.orEmpty()}; " +
                        "responses=${attempts.size}; failureReason=${failureReason.ifBlank { "none" }}",
                )
            }
        }

        assertFalse("The live player API returned no aggregate stream metadata", summaries.isEmpty())
        System.out.println(
            "playerClientComparisonResources; videos=${videoIds.size}; requests=${summaries.size}; " +
                "cpuMs=${Process.getElapsedCpuTime() - cpuStarted}; " +
                "wallMs=${SystemClock.elapsedRealtime() - wallStarted}; pssKiB=$pssStarted->${Debug.getPss()}",
        )
    }

    @Test
    fun reportsAlternativePublicPlayerClientVariants() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e runPlayerVariantDiagnostic true to run this bounded client comparison",
            arguments.getString("runPlayerVariantDiagnostic") == "true",
        )
        val videoIds = arguments.getString("playerVariantVideoIds").orEmpty()
            .take(256)
            .split(',')
            .map(String::trim)
            .filter(VIDEO_ID::matches)
            .distinct()
            .take(2)
            .ifEmpty {
                listOf(arguments.getString("playerVariantVideoId")
                    ?.takeIf { VIDEO_ID.matches(it) }
                    ?: DEFAULT_VIDEO_ID)
            }
        val configuration = readPublicConfiguration()
        val variants = listOf(
            PlayerVariant(
                name = "IOS",
                version = "21.26.4",
                headerId = "5",
                userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                fields = JSONObject()
                    .put("deviceMake", "Apple")
                    .put("deviceModel", "iPhone16,2")
                    .put("osName", "iPhone")
                    .put("osVersion", "18.3.2.22D82"),
            ),
            PlayerVariant(
                name = "MWEB",
                version = "2.20260708.05.00",
                headerId = "2",
                userAgent = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 " +
                    "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",
            ),
            PlayerVariant(
                name = "ANDROID_CURRENT",
                version = "21.26.364",
                headerId = "3",
                userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
                clientName = "ANDROID",
                fields = JSONObject()
                    .put("androidSdkVersion", 30)
                    .put("osName", "Android")
                    .put("osVersion", "11")
                    .put("deviceMake", "Google")
                    .put("deviceModel", "Pixel 5"),
            ),
            PlayerVariant(
                name = "ANDROID_VR",
                version = "1.65.10",
                headerId = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; " +
                    "eureka-user Build/SQ3A.220605.009.A1) gzip",
                fields = JSONObject()
                    .put("deviceMake", "Oculus")
                    .put("deviceModel", "Quest 3")
                    .put("androidSdkVersion", 32)
                    .put("osName", "Android")
                    .put("osVersion", "12L"),
            ),
            PlayerVariant(
                name = "TVHTML5_DOWNGRADED",
                version = "5.20260707",
                headerId = "7",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
                clientName = "TVHTML5",
            ),
            PlayerVariant(
                name = "TVHTML5",
                version = "7.20260707.07.00",
                headerId = "7",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold " +
                    "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
            ),
        )

        val cpuStarted = android.os.Process.getElapsedCpuTime()
        val wallStarted = android.os.SystemClock.elapsedRealtime()
        val pssStarted = android.os.Debug.getPss()
        videoIds.forEach { videoId ->
            variants.forEach { variant ->
                val response = runCatching { requestPublicPlayer(videoId, configuration, variant) }
                val root = response.getOrNull()
                val status = root?.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                val streaming = root?.optJSONObject("streamingData")
                val counts = streaming?.let(::summarizeFormats)
                System.out.println(
                    "playerVariantAggregate; video=$videoId; client=${variant.name}; " +
                        "playability=${status.ifBlank { "NO_RESPONSE" }}; " +
                        "formats=${counts?.get("formats") ?: 0}; direct=${counts?.get("direct") ?: 0}; " +
                        "signatureCipher=${counts?.get("cipher") ?: 0}; obfuscatedS=${counts?.get("obfuscatedS") ?: 0}; " +
                        "plainSig=${counts?.get("plainSig") ?: 0}; directN=${counts?.get("directN") ?: 0}; " +
                        "dash=${streaming?.optString("dashManifestUrl").orEmpty().isNotBlank()}; " +
                        "hls=${streaming?.optString("hlsManifestUrl").orEmpty().isNotBlank()}; " +
                        "serverAbr=${streaming?.optString("serverAbrStreamingUrl").orEmpty().isNotBlank()}; " +
                        "httpStatus=${response.exceptionOrNull()?.let(::safeHttpStatus) ?: "200"}",
                )
                val inspectNChallenge = arguments.getString("runPlayerVariantNChallenge") == "true" ||
                    arguments.getString("runPlayerVariantNRange") == "true"
                val probeNRange = arguments.getString("runPlayerVariantNRange") == "true"
                if (variant.name == "MWEB" && inspectNChallenge) {
                    val challenge = response.getOrNull()?.optJSONObject("streamingData")?.let { streaming ->
                        runCatching {
                            inspectMwebNChallenge(streaming, configuration.playerScriptUrl, variant.userAgent, probeNRange)
                        }
                            .getOrElse { failure ->
                                NChallengeResult(
                                    "error",
                                    0,
                                    0,
                                    "error",
                                    failureType = failure.javaClass.simpleName + ":" +
                                        failure.stackTrace.firstOrNull()?.let { "${it.methodName}:${it.lineNumber}" }.orEmpty(),
                                )
                            }
                    } ?: NChallengeResult("no_streaming_data", 0, 0, "not_run")
                    System.out.println(
                        "playerVariantNChallenge; video=$videoId; client=MWEB; outcome=${challenge.outcome}; " +
                            "candidateCount=${challenge.candidateCount}; transformedCount=${challenge.transformedCount}; " +
                            "batchAccepted=${challenge.batchAccepted}; recoveryAccepted=${challenge.recoveryAccepted}; " +
                            "markerAccepted=${challenge.markerAccepted}; " +
                            "nOnlyAccepted=${challenge.nOnlyAccepted}; " +
                            "outputRejected=${challenge.outputRejected}; executionFailed=${challenge.executionFailed}; " +
                            "urlTransformFound=${challenge.urlTransformFound}; urlProgramBytes=${challenge.urlProgramBytes}; " +
                            "nTransformFound=${challenge.nTransformFound}; nProgramBytes=${challenge.nProgramBytes}; " +
                            "rejectedReasons=${challenge.rejectedReasons}; recoveryReasons=${challenge.recoveryReasons}; " +
                            "failureType=${challenge.failureType}; " +
                            "httpStatus=${challenge.httpStatus}; bytes=${challenge.bytes}",
                    )
                }
            }
        }
        System.out.println(
            "playerVariantDiagnosticResources; videos=${videoIds.size}; clients=${variants.size}; " +
                "cpuMs=${android.os.Process.getElapsedCpuTime() - cpuStarted}; " +
                "wallMs=${android.os.SystemClock.elapsedRealtime() - wallStarted}; " +
                "pssKiB=$pssStarted->${android.os.Debug.getPss()}",
        )
    }

    private fun readPublicConfiguration(): PublicConfiguration {
        val connection = URL("https://www.youtube.com/?hl=en").openConnection() as HttpURLConnection
        connection.connectTimeout = REQUEST_TIMEOUT_MS
        connection.readTimeout = REQUEST_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", BASE_USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        try {
            if (connection.responseCode !in 200..299) throw IOException("YouTube configuration request failed.")
            val html = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_CONFIG_BYTES) throw IOException("YouTube configuration response is too large.")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            val apiKey = captureConfigValue(html, "INNERTUBE_API_KEY")
                ?: throw IOException("YouTube player API configuration is unavailable.")
            val signatureTimestamp = Regex("""[\"']STS[\"']\s*:\s*(\d{1,10})""")
                .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val playerScriptUrl = captureConfigValue(html, "PLAYER_JS_URL").orEmpty()
            return PublicConfiguration(apiKey, signatureTimestamp, playerScriptUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun captureConfigValue(html: String, key: String): String? {
        val pattern = Regex("""[\"']${Regex.escape(key)}[\"']\s*:\s*[\"']([^\"']{1,256})[\"']""")
        return pattern.find(html)?.groupValues?.getOrNull(1)
    }

    private fun requestPublicPlayer(
        videoId: String,
        configuration: PublicConfiguration,
        variant: PlayerVariant,
    ): JSONObject {
        val client = JSONObject()
            .put("clientName", variant.clientName)
            .put("clientVersion", variant.version)
            .put("hl", "en")
            .put("gl", "US")
            .put("userAgent", variant.userAgent)
        val fields = variant.fields.keys()
        while (fields.hasNext()) {
            val key = fields.next()
            client.put(key, variant.fields.opt(key))
        }
        val payload = JSONObject()
            .put("videoId", videoId)
            .put("context", JSONObject().put("client", client))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (configuration.signatureTimestamp > 0) payload.put(
            "playbackContext",
            JSONObject().put(
                "contentPlaybackContext",
                JSONObject().put("signatureTimestamp", configuration.signatureTimestamp),
            ),
        )
        val connection = URL(
            "https://www.youtube.com/youtubei/v1/player?key=${configuration.apiKey}&prettyPrint=false",
        ).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = REQUEST_TIMEOUT_MS
        connection.readTimeout = REQUEST_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", variant.userAgent)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Origin", "https://www.youtube.com")
        connection.setRequestProperty("Referer", "https://www.youtube.com/")
        connection.setRequestProperty("X-Youtube-Client-Name", variant.headerId)
        connection.setRequestProperty("X-Youtube-Client-Version", variant.version)
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("YouTube player client HTTP $responseCode.")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_PLAYER_RESPONSE_BYTES) throw IOException("YouTube player response is too large.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun summarizeFormats(streaming: JSONObject): Map<String, Int> {
        var formatCount = 0
        var directCount = 0
        var cipherCount = 0
        var obfuscatedSignatureCount = 0
        var plainSignatureCount = 0
        var directNCount = 0
        for (key in arrayOf("formats", "adaptiveFormats")) {
            val formats = streaming.optJSONArray(key) ?: continue
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                formatCount++
                val direct = format.optString("url")
                if (direct.isNotBlank()) {
                    directCount++
                    if (runCatching { android.net.Uri.parse(direct).getQueryParameter("n").orEmpty() }.getOrDefault("").isNotBlank()) {
                        directNCount++
                    }
                }
                val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
                if (cipher.isNotBlank()) {
                    cipherCount++
                    val fields = android.net.Uri.parse("https://cipher.invalid/?$cipher")
                    if (!fields.getQueryParameter("s").isNullOrBlank()) obfuscatedSignatureCount++
                    if (!fields.getQueryParameter("sig").isNullOrBlank() || !fields.getQueryParameter("signature").isNullOrBlank()) {
                        plainSignatureCount++
                    }
                }
            }
        }
        return mapOf(
            "formats" to formatCount,
            "direct" to directCount,
            "cipher" to cipherCount,
            "obfuscatedS" to obfuscatedSignatureCount,
            "plainSig" to plainSignatureCount,
            "directN" to directNCount,
        )
    }

    private fun inspectMwebNChallenge(
        streaming: JSONObject,
        rawPlayerScriptUrl: String,
        userAgent: String,
        probeRange: Boolean,
    ): NChallengeResult {
        var stage = "start"
        val scriptUrl = YoutubePlayerScriptCache.normalizeUrl(rawPlayerScriptUrl)
            ?: return NChallengeResult("player_script_missing", 0, 0, "not_run")
        val executableFormats = listOf("formats", "adaptiveFormats").flatMap { key ->
            val formats = streaming.optJSONArray(key) ?: return@flatMap emptyList()
            (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
                .mapNotNull { format ->
                    val url = format.optString("url")
                    if (url.isBlank()) return@mapNotNull null
                    if (runCatching { android.net.Uri.parse(url).getQueryParameter("n").orEmpty() }.getOrDefault("").isBlank()) {
                        return@mapNotNull null
                    }
                    format to url
                }
        }.take(MAX_N_CHALLENGE_CANDIDATES)
        if (executableFormats.isEmpty()) return NChallengeResult("n_missing", 0, 0, "not_run")

        val playerScript = YoutubePlayerScriptCache().load(scriptUrl)
        stage = "player_script_loaded"
        val indexedNCallSites = Regex(
            """\([A-Za-z_$][A-Za-z0-9_$]*\([A-Za-z_$][A-Za-z0-9_$]*\),[A-Za-z_$][A-Za-z0-9_$]*=[A-Za-z_$][A-Za-z0-9_$]*\.j\.n\|\|null\)\s*&&\([A-Za-z_$][A-Za-z0-9_$]*=([A-Za-z_$][A-Za-z0-9_$]*)\[(\d{1,3})\]\(""",
        ).findAll(playerScript.source).take(5).map { match ->
            "${match.groupValues[1]}[${match.groupValues[2]}]"
        }.toList()
        val nMarkerCounts = listOf(
            "getN" to Regex("""\.get\(\s*["']n["']\s*\)"""),
            "setN" to Regex("""\.set\(\s*["']n["']"""),
            "memberJn" to Regex("""\.j\.n\b"""),
            "memberN" to Regex("""\.n\b"""),
            "charCodeN" to Regex("""fromCharCode\(110\)"""),
        ).associate { (name, pattern) -> name to pattern.findAll(playerScript.source).take(MAX_JS_MARKER_MATCHES).count() }
        val getNContext = Regex("""\.get\(\s*["']n["']\s*\)""").find(playerScript.source)?.let { match ->
            val start = (match.range.first - MAX_N_CONTEXT_CHARS).coerceAtLeast(0)
            val end = (match.range.last + MAX_N_CONTEXT_CHARS + 1).coerceAtMost(playerScript.source.length)
            sanitizePlayerContext(playerScript.source.substring(start, end))
        }.orEmpty()
        val locatorEvents = mutableListOf<String>()
        var executable = YoutubePlayerTransformLocator.locateExecutable(
            playerScript.source,
            onDiagnosticStage = { event: String ->
                if ((event.startsWith("locatorGetNContext;") ||
                        event.startsWith("locatorNPropertyContext;") ||
                        event.startsWith("locatorNStringContext;") ||
                        event.startsWith("locatorAlrMarker;") ||
                        event.startsWith("locatorFunctionReference;") ||
                        event.startsWith("locatorSetNContext;") ||
                        event.startsWith("locatorMarkerCandidate;") ||
                        event.startsWith("locatorCandidates;")) && locatorEvents.size < MAX_LOCATOR_EVENTS
                ) locatorEvents.add(event.take(MAX_LOCATOR_EVENT_CHARS))
            },
            includeMarkerCandidates = true,
        )
        locatorEvents.forEach { System.out.println("playerVariantLocatorShape; $it") }
        System.out.println("playerVariantNStage; stage=locator_complete")
        System.out.println(
            "playerVariantNCallSite; indexedNCallSites=${indexedNCallSites.size}; " +
                "arrayEntries=${indexedNCallSites.joinToString(",").ifBlank { "none" }}; " +
                "markerCounts=${nMarkerCounts.entries.joinToString(",") { "${it.key}:${it.value}" }}; " +
                "urlFunction=${executable.functions.urlThrottling.orEmpty()}; " +
                "getNContext=${getNContext.ifBlank { "none" }}",
        )
        stage = "locator_complete"
        val runtime = YoutubePlayerJavaScriptRuntime()
        val fullPlayerBundleProbe = InstrumentationRegistry.getArguments().getString("playerVariantFullBundleProbe") == "true"
        val requestedMarker = InstrumentationRegistry.getArguments().getString("playerVariantMarker")
            ?.takeIf { Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,127}$").matches(it) }
        val markerCandidates = executable.markerCandidates
            .filter { requestedMarker == null || it.name == requestedMarker }
            .take(MAX_MARKER_URL_CANDIDATES)
        val markerInspections = ArrayList<UrlObjectProbe>()
        markerCandidates.forEach { marker ->
            if (marker.isAsync) {
                markerInspections.add(UrlObjectProbe(marker.name, "async"))
                return@forEach
            }
            var candidate = marker
            var failure: Throwable? = null
            var result: JSONObject? = null
            val requiredSymbols = LinkedHashSet<String>()
            val targetGraphEvents = ArrayList<String>()
            for (attemptIndex in 0..MAX_MARKER_URL_RECOVERIES) {
                val candidateSource = if (fullPlayerBundleProbe) playerScript.source else candidate.program
                val attempt = runCatching {
                    kotlinx.coroutines.runBlocking {
                        runtime.inspectUrlCandidate(
                            candidateSource,
                            candidate.name,
                            timeoutMillis = if (fullPlayerBundleProbe) 15_000L else 5_000L,
                            inputUrl = executableFormats.first().second,
                        )
                    }
                }
                val output = attempt.getOrNull()
                result = output?.let { runCatching { JSONObject(it) }.getOrNull() }
                failure = attempt.exceptionOrNull()
                if (result?.optBoolean("ok") == true) break
                val unresolved = failure?.let { YoutubePlayerJavaScriptRuntime.unresolvedIdentifier(it) }
                if (unresolved.isNullOrBlank() || !requiredSymbols.add(unresolved) ||
                    requiredSymbols.size > MAX_MARKER_URL_RECOVERIES
                ) break
                val rebuilt = runCatching {
                    YoutubePlayerTransformLocator.locateMarkerCandidate(
                        playerScript.source,
                        marker.name,
                        onDiagnosticStage = { event ->
                            if ((event.startsWith("locatorTargetSymbol;") ||
                                    event.startsWith("locatorDiagnosticIdentifier;")) && targetGraphEvents.size < 4
                            ) {
                                targetGraphEvents.add(event.take(MAX_LOCATOR_EVENT_CHARS))
                            }
                        },
                        diagnosticIdentifiers = requiredSymbols,
                    )
                }
                if (rebuilt.isFailure) {
                    failure = rebuilt.exceptionOrNull()
                    break
                }
                candidate = rebuilt.getOrNull() ?: break
            }
            targetGraphEvents.forEach { event -> System.out.println("playerVariantTargetGraph; $event") }
            val methods = result?.optString("methods").orEmpty().split(',').filter(String::isNotBlank)
            val ownNames = result?.optString("ownNames").orEmpty()
                .replace(Regex("[^A-Za-z0-9_,$-]"), "")
                .take(180)
            val innerType = result?.optString("innerType").orEmpty()
                .takeIf { it in setOf("string", "object", "function", "undefined", "number", "boolean") }
                .orEmpty()
            val trigger = result?.optString("trigger").orEmpty()
                .takeIf { Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,127}$").matches(it) }.orEmpty()
            val error = result?.optString("error").orEmpty().ifBlank { failure?.message.orEmpty() }
                .replace(Regex("[^A-Za-z0-9 _-]"), " ").take(96)
            val jsStackHint = result?.optString("stack").orEmpty()
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .replace(Regex("[^A-Za-z0-9_.$:-]"), " ")
                .take(96)
            val unresolved = failure?.let { YoutubePlayerJavaScriptRuntime.unresolvedIdentifier(it) }.orEmpty()
            val transformedUrl = result?.optString("url").orEmpty()
            val inputN = runCatching { android.net.Uri.parse(executableFormats.first().second).getQueryParameter("n") }.getOrNull()
            val outputN = result?.optString("n")?.takeIf(String::isNotBlank)
            val returnedUri = runCatching { URI(transformedUrl) }.getOrNull()
            val inputUri = runCatching { URI(executableFormats.first().second) }.getOrNull()
            val outputQueryN = runCatching { android.net.Uri.parse(transformedUrl).getQueryParameter("n") }.getOrNull()
            val playerNChanged = inputN != null && outputN != null && inputN != outputN
            val urlNChanged = inputN != null && outputQueryN != null && inputN != outputQueryN
            val hostPathPreserved = inputUri != null && returnedUri != null &&
                inputUri.scheme.equals("https", ignoreCase = true) &&
                returnedUri.scheme.equals(inputUri.scheme, ignoreCase = true) &&
                returnedUri.host.equals(inputUri.host, ignoreCase = true) &&
                returnedUri.port == inputUri.port && returnedUri.rawPath == inputUri.rawPath &&
                inputUri.rawUserInfo == null && returnedUri.rawUserInfo == null
            val acceptedNTransform = result?.optBoolean("ok") == true &&
                YoutubePlayerUrlTransformer.acceptUrlNTransform(executableFormats.first().second, transformedUrl) != null
            val failureFrames = failure?.stackTrace?.take(4)?.joinToString(",") {
                "${it.className.substringAfterLast('.')}:${it.methodName}:${it.lineNumber}"
            }.orEmpty()
            markerInspections.add(
                UrlObjectProbe(
                    name = marker.name,
                    outcome = if (result?.optBoolean("ok") == true) "ok" else "failed",
                    trigger = trigger,
                    methodCount = methods.size,
                    ownNames = ownNames,
                    innerType = innerType,
                    unresolved = unresolved,
                    error = (error + " js=" + jsStackHint + " stack=" + failureFrames).take(220),
                    recoveredSymbols = requiredSymbols.size,
                    nChanged = acceptedNTransform,
                    playerNChanged = playerNChanged,
                    urlNChanged = urlNChanged,
                    hostPathPreserved = hostPathPreserved,
                ),
            )
        }
        markerInspections.forEach { probe ->
            System.out.println(
                "playerVariantUrlObjectProbe; name=${probe.name}; outcome=${probe.outcome}; trigger=${probe.trigger}; " +
                    "methodCount=${probe.methodCount}; unresolved=${probe.unresolved}; error=${probe.error}; " +
                    "recoveredSymbols=${probe.recoveredSymbols}; source=${if (fullPlayerBundleProbe) "full_bundle" else "isolated"}; " +
                    "ownNames=${probe.ownNames}; innerType=${probe.innerType}; " +
                    "playerNChanged=${probe.playerNChanged}; urlNChanged=${probe.urlNChanged}; " +
                    "hostPathPreserved=${probe.hostPathPreserved}; " +
                    "nChanged=${probe.nChanged}",
            )
        }
        val fallbackNames = markerCandidates.filterNot { it.isAsync }.map { it.name }
        val fallbackStatus = if (fullPlayerBundleProbe && fallbackNames.isNotEmpty() && executableFormats.isNotEmpty()) {
            runProductionUrlObjectFallback(runtime, playerScript.source, fallbackNames, executableFormats.first().second)
        } else "not_run"
        System.out.println(
            "playerVariantUrlObjectRuntimeProbe; candidateCount=${fallbackNames.size}; " +
                "status=$fallbackStatus",
        )
        val objectMutators = markerInspections.count { it.outcome == "ok" && it.nChanged }
        System.out.println(
            "playerVariantUrlObjectProbeSummary; candidates=${markerCandidates.size}; " +
                "async=${markerCandidates.count { it.isAsync }}; nMutators=$objectMutators",
        )
        val function = executable.functions.urlThrottling
        val results = if (function == null) {
            emptyList()
        } else {
            val calls = executableFormats.map { (_, url) -> YoutubePlayerJavaScriptRuntime.FunctionCall(function, url) }
            System.out.println("playerVariantNStage; stage=url_batch_start")
            kotlinx.coroutines.runBlocking { runtime.executeFullPlayerFunctions(playerScript.source, calls) }
                .also {
                    System.out.println("playerVariantNStage; stage=url_batch_complete")
                    stage = "url_batch_complete"
                }
        }
        var batchAccepted = 0
        var markerAccepted = 0
        var recoveryAccepted = 0
        var nOnlyAccepted = 0
        var outputRejected = 0
        var executionFailed = 0
        val rejectedReasons = mutableMapOf<String, Int>()
        val recoveryReasons = mutableMapOf<String, Int>()
        val transformed = mutableListOf<String>()
        if (function != null) executableFormats.zip(results).forEach { (candidate, result) ->
            val accepted = result?.let { YoutubePlayerUrlTransformer.acceptUrlNTransform(candidate.second, it) }
            if (accepted != null) {
                batchAccepted++
                transformed.add(accepted)
            } else if (result != null) {
                outputRejected++
                rejectedReasons.increment(classifyNOutput(candidate.second, result))
                val recovered = runCatching {
                    val value = kotlinx.coroutines.runBlocking {
                        executeUrlTransformWithRecovery(
                            playerScript.source,
                            executable,
                            candidate.second,
                            runtime,
                        ) { expanded -> executable = expanded }
                    }
                    value?.let { YoutubePlayerUrlTransformer.acceptUrlNTransform(candidate.second, it) }
                }.getOrElse {
                    executionFailed++
                    recoveryReasons.increment("recovery_exception")
                    null
                }
                if (recovered != null) {
                    recoveryAccepted++
                    transformed.add(recovered)
                } else {
                    val fallback = runCatching {
                        kotlinx.coroutines.runBlocking { executeNOnlyFallback(candidate.second, executable, runtime) }
                    }.getOrNull()
                    if (fallback != null) {
                        nOnlyAccepted++
                        transformed.add(fallback)
                    }
                }
            } else {
                executionFailed++
                recoveryReasons.increment("batch_function_error")
                val recovered = runCatching {
                    val value = kotlinx.coroutines.runBlocking {
                        executeUrlTransformWithRecovery(
                            playerScript.source,
                            executable,
                            candidate.second,
                            runtime,
                        ) { expanded -> executable = expanded }
                    }
                    value?.let { YoutubePlayerUrlTransformer.acceptUrlNTransform(candidate.second, it) }
                }.getOrElse {
                    executionFailed++
                    recoveryReasons.increment("recovery_exception")
                    null
                }
                if (recovered != null) {
                    recoveryAccepted++
                    transformed.add(recovered)
                } else {
                    val fallback = runCatching {
                        kotlinx.coroutines.runBlocking { executeNOnlyFallback(candidate.second, executable, runtime) }
                    }.getOrNull()
                    if (fallback != null) {
                        nOnlyAccepted++
                        transformed.add(fallback)
                    }
                }
            }
        }
        val markerNames = executable.markerCandidateNames.take(MAX_MARKER_URL_CANDIDATES)
        if (function == null && markerNames.isNotEmpty()) {
            stage = "marker_url_batch_start"
            val markerResults = runCatching {
                kotlinx.coroutines.runBlocking {
                    runtime.executeFullPlayerUrlObjectNTransforms(
                        playerScript.source,
                        markerNames,
                        executableFormats.map { it.second },
                    )
                }
            }.getOrDefault(emptyList())
            executableFormats.zip(markerResults).forEach { (candidate, result) ->
                val accepted = result.url?.let { YoutubePlayerUrlTransformer.acceptUrlNTransform(candidate.second, it) }
                if (accepted != null) {
                    markerAccepted++
                    transformed.add(accepted)
                }
            }
            stage = "marker_url_batch_complete"
        }
        val range = if (probeRange) transformed.firstOrNull()?.let { boundedRange(it, userAgent) } else null
        val outcome = when {
            transformed.isEmpty() -> "transform_rejected"
            range == null -> "range_not_run"
            range.status == HttpURLConnection.HTTP_PARTIAL && range.bytes == MAX_RANGE_BYTES -> "range_ok"
            else -> "range_rejected"
        }
        return NChallengeResult(
            outcome,
            executableFormats.size,
            transformed.size,
            range?.status?.toString() ?: "not_run",
            range?.bytes ?: 0,
            batchAccepted,
            recoveryAccepted,
            nOnlyAccepted,
            outputRejected,
            executionFailed,
            rejectedReasons.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "none" },
            recoveryReasons.entries.joinToString(",") { "${it.key}:${it.value}" }.ifBlank { "none" },
            executable.functions.urlThrottling != null,
            executable.urlThrottlingProgram?.length ?: 0,
            executable.functions.throttling != null,
            executable.throttlingProgram?.length ?: 0,
            failureType = stage,
            markerAccepted = markerAccepted,
        )
    }

    private fun runProductionUrlObjectFallback(
        runtime: YoutubePlayerJavaScriptRuntime,
        source: String,
        candidates: List<String>,
        inputUrl: String,
    ): String = try {
        val result = kotlinx.coroutines.runBlocking {
            runtime.executeFullPlayerUrlObjectNTransforms(source, candidates, listOf(inputUrl)).firstOrNull()
        }
        if (result == null) "no_result" else {
            val output = result.url
            if (output.isNullOrBlank()) {
                when {
                    !result.candidateFound -> "candidate_unresolved"
                    result.methodCount == 0 -> "no_methods"
                    !result.nChanged -> "n_unchanged"
                    else -> "empty_serialized_url"
                }
            } else if (YoutubePlayerUrlTransformer.acceptUrlNTransform(inputUrl, output) != null) {
                "accepted"
            } else {
                val input = runCatching { URI(inputUrl) }.getOrNull()
                val returned = runCatching { URI(output) }.getOrNull()
                val sameHostPath = input != null && returned != null &&
                    input.scheme.equals("https", true) && returned.scheme.equals(input.scheme, true) &&
                    returned.host.equals(input.host, true) && returned.port == input.port &&
                    returned.rawPath == input.rawPath && input.rawUserInfo == null && returned.rawUserInfo == null
                if (!sameHostPath) "host_path_rejected" else {
                    val inputN = android.net.Uri.parse(inputUrl).getQueryParameter("n")
                    val outputN = android.net.Uri.parse(output).getQueryParameter("n")
                    if (inputN == outputN) "n_unchanged" else "other_rejected"
                }
            }
        }
    } catch (failure: Exception) {
        val detail = generateSequence(failure as Throwable?) { it.cause }
            .take(4)
            .joinToString(" | ") { it.message ?: it.javaClass.simpleName }
            .replace(Regex("[^A-Za-z0-9 _:-]"), " ")
            .take(180)
        "execution_error:$detail"
    }

    private fun classifyNOutput(originalUrl: String, output: String): String {
        if (output.isBlank()) return "empty"
        val original = runCatching { java.net.URI(originalUrl) }.getOrNull() ?: return "source_uri_invalid"
        val transformed = runCatching { java.net.URI(output) }.getOrNull() ?: return "output_uri_invalid"
        if (!original.scheme.equals("https", true) ||
            !original.scheme.equals(transformed.scheme, true) ||
            !original.host.equals(transformed.host, true) || original.port != transformed.port ||
            original.rawPath != transformed.rawPath || original.rawUserInfo != null || transformed.rawUserInfo != null
        ) return "origin_or_path_changed"
        val beforeN = queryValue(original.rawQuery, "n") ?: return "source_n_missing"
        val afterN = queryValue(transformed.rawQuery, "n") ?: return "output_n_missing"
        return if (afterN == beforeN) "n_unchanged" else "accepted"
    }

    private fun queryValue(query: String?, name: String): String? = query?.split('&')
        ?.firstNotNullOfOrNull { part ->
            val key = runCatching { java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8") }.getOrNull()
            if (key == name) runCatching {
                java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }.getOrNull() else null
        }

    private fun sanitizePlayerContext(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\'' || char == '"' || char == '`') {
                append("<literal>")
                index++
                while (index < value.length) {
                    if (value[index] == '\\') {
                        index = (index + 2).coerceAtMost(value.length)
                    } else if (value[index++] == char) break
                }
            } else if (char == '/' && value.getOrNull(index + 1) == '*') {
                append("<comment>")
                index += 2
                while (index + 1 < value.length && !(value[index] == '*' && value[index + 1] == '/')) index++
                index = (index + 2).coerceAtMost(value.length)
            } else if (char == '/' && value.getOrNull(index + 1) == '/') {
                append("<comment>")
                index += 2
                while (index < value.length && value[index] !in setOf('\n', '\r')) index++
            } else {
                append(char)
                index++
            }
        }
    }.take(MAX_N_CONTEXT_CHARS * 2)

    private suspend fun executeNOnlyFallback(
        originalUrl: String,
        executable: YoutubePlayerTransformLocator.ExecutableFunctions,
        runtime: YoutubePlayerJavaScriptRuntime,
    ): String? {
        val function = executable.functions.throttling ?: return null
        val source = executable.throttlingProgram?.takeIf(String::isNotBlank) ?: return null
        val n = runCatching { android.net.Uri.parse(originalUrl).getQueryParameter("n") }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: return null
        val transformedN = runtime.executeFunction(source, function, n)
        val updated = YoutubePlayerUrlTransformer.applyN(originalUrl, transformedN) ?: return null
        return updated.takeIf { YoutubePlayerUrlTransformer.acceptUrlNTransform(originalUrl, it) != null }
    }

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = getOrDefault(key, 0) + 1
    }

    private suspend fun executeUrlTransformWithRecovery(
        playerScript: String,
        initial: YoutubePlayerTransformLocator.ExecutableFunctions,
        directUrl: String,
        runtime: YoutubePlayerJavaScriptRuntime,
        onExpanded: (YoutubePlayerTransformLocator.ExecutableFunctions) -> Unit,
    ): String? {
        var executable = initial
        val requiredDependencies = executable.requiredDependencies.toMutableSet()
        for (attempt in 0..MAX_URL_RECOVERY_ATTEMPTS) {
            val function = executable.functions.urlThrottling ?: return null
            val source = executable.urlThrottlingProgram?.takeIf(String::isNotBlank) ?: return null
            try {
                return runtime.executeFunction(source, function, directUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val unresolved = YoutubePlayerJavaScriptRuntime.unresolvedIdentifier(failure) ?: return null
                if (!requiredDependencies.add(unresolved) || requiredDependencies.size > MAX_URL_RECOVERY_DEPENDENCIES) return null
                val expanded = YoutubePlayerTransformLocator.locateExecutable(
                    playerScript,
                    requiredDependencies = requiredDependencies,
                )
                if (expanded.urlThrottlingProgram == source) return null
                executable = expanded
                onExpanded(expanded)
            }
        }
        return null
    }

    private fun boundedRange(url: String, userAgent: String): RangeResult? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = REQUEST_TIMEOUT_MS
        connection.readTimeout = REQUEST_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Range", "bytes=0-${MAX_RANGE_BYTES - 1}")
        return try {
            val status = connection.responseCode
            if (status !in 200..299) return RangeResult(status, 0)
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var count = 0
                while (count < MAX_RANGE_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_RANGE_BYTES - count))
                    if (read < 0) break
                    count += read
                }
                count
            }
            RangeResult(status, bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun safeHttpStatus(error: Throwable): String =
        Regex("HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.getOrNull(1) ?: "error"

    private data class PlayerVariant(
        val name: String,
        val version: String,
        val headerId: String,
        val userAgent: String,
        val clientName: String = name,
        val fields: JSONObject = JSONObject(),
    )

    private data class UrlObjectProbe(
        val name: String,
        val outcome: String = "failed",
        val trigger: String = "",
        val methodCount: Int = 0,
        val ownNames: String = "",
        val innerType: String = "",
        val unresolved: String = "",
        val error: String = "",
        val recoveredSymbols: Int = 0,
        val playerNChanged: Boolean = false,
        val urlNChanged: Boolean = false,
        val hostPathPreserved: Boolean = false,
        val nChanged: Boolean = false,
    )

    private data class PublicConfiguration(val apiKey: String, val signatureTimestamp: Int, val playerScriptUrl: String)
    private data class NChallengeResult(
        val outcome: String,
        val candidateCount: Int,
        val transformedCount: Int,
        val httpStatus: String,
        val bytes: Int = 0,
        val batchAccepted: Int = 0,
        val recoveryAccepted: Int = 0,
        val nOnlyAccepted: Int = 0,
        val outputRejected: Int = 0,
        val executionFailed: Int = 0,
        val rejectedReasons: String = "none",
        val recoveryReasons: String = "none",
        val urlTransformFound: Boolean = false,
        val urlProgramBytes: Int = 0,
        val nTransformFound: Boolean = false,
        val nProgramBytes: Int = 0,
        val failureType: String = "none",
        val markerAccepted: Int = 0,
    )
    private data class RangeResult(val status: Int, val bytes: Int)

    companion object {
        private const val DEFAULT_VIDEO_ID = "aqz-KE-bpKQ"
        private const val MAX_VIDEO_IDS = 3
        private const val REQUEST_TIMEOUT_MS = 10_000
        private const val MAX_CONFIG_BYTES = 4 * 1024 * 1024
        private const val MAX_PLAYER_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val MAX_N_CHALLENGE_CANDIDATES = 3
        private const val MAX_MARKER_URL_CANDIDATES = 3
        private const val MAX_MARKER_URL_RECOVERIES = 8
        private const val MAX_URL_RECOVERY_ATTEMPTS = 8
        private const val MAX_URL_RECOVERY_DEPENDENCIES = 8
        private const val MAX_JS_MARKER_MATCHES = 1000
        private const val MAX_N_CONTEXT_CHARS = 112
        private const val MAX_LOCATOR_EVENTS = 20
        private const val MAX_LOCATOR_EVENT_CHARS = 1200
        private const val MAX_RANGE_BYTES = 65_536
        private const val BASE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
