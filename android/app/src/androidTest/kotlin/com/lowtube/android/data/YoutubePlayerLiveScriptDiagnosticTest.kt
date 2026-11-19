package com.lowtube.android.data

import android.os.Debug
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live player probe. It never starts Media3 playback and logs resource metrics only. */
@RunWith(AndroidJUnit4::class)
class YoutubePlayerLiveScriptDiagnosticTest {
    @Test
    fun reportsLivePlayerResolverCoverageAndResourceUse() {
        assumeTrue(
            "Pass -e runLivePlayerDiagnostic true to run this bounded network diagnostic",
            InstrumentationRegistry.getArguments().getString("runLivePlayerDiagnostic") == "true",
        )
        val diagnosticStartedAt = System.nanoTime()
        val arguments = InstrumentationRegistry.getArguments()
        val pinnedScriptUrl = arguments.getString("playerScriptUrl")
            ?.takeIf { Regex("""^https://www\.youtube\.com/s/player/[A-Za-z0-9_./-]+/base\.js$""").matches(it) }
        val scriptReference = pinnedScriptUrl ?: run {
            val watchStartedAt = System.nanoTime()
            val watchCpuStarted = Process.getElapsedCpuTime()
            val watchPssStarted = Debug.getPss()
            val html = readBounded("https://www.youtube.com/watch?v=aqz-KE-bpKQ", MAX_WATCH_BYTES)
            logStage("watch", watchStartedAt, watchCpuStarted, watchPssStarted, html.length)
            PLAYER_SCRIPT_REFERENCE.find(html)?.groupValues?.getOrNull(1)
                ?: throw IOException("The public watch response did not expose a player script URL.")
        }
        val scriptUrl = YoutubePlayerScriptCache.normalizeUrl(scriptReference)
            ?: throw IOException("The public watch response exposed an unexpected player script URL.")
        val scriptStartedAt = System.nanoTime()
        val scriptCpuStarted = Process.getElapsedCpuTime()
        val scriptPssStarted = Debug.getPss()
        val script = YoutubePlayerScriptCache { readBounded(it, YoutubePlayerScriptCache.MAX_SCRIPT_BYTES) }
            .load(scriptUrl)
        logStage("script", scriptStartedAt, scriptCpuStarted, scriptPssStarted, script.source.length)
        System.out.println(
            "liveSignatureLexicalSummary; signature=${Regex("signature").findAll(script.source).count()}; " +
                "signatureCipher=${Regex("signatureCipher").findAll(script.source).count()}; " +
                "decodeURIComponent=${Regex("decodeURIComponent").findAll(script.source).count()}; " +
                "splitEmpty=${Regex("""\.split\s*\(\s*["']{2}\s*\)""").findAll(script.source).count()}; " +
                "joinEmpty=${Regex("""\.join\s*\(\s*["']{2}\s*\)""").findAll(script.source).count()}; " +
                "alrMarker=${Regex("""\.set\(\s*["']alr["']\s*,\s*["']yes["']""").findAll(script.source).count()}",
        )

        val locatorStartedAt = System.nanoTime()
        val locatorCpuStarted = Process.getElapsedCpuTime()
        val locatorPssStarted = Debug.getPss()
        val diagnosticSymbol = arguments.getString("diagnosticSymbol")
            ?.takeIf { Regex("""^[A-Za-z_$][A-Za-z0-9_$]{0,127}$""").matches(it) }
        val requiredDependencies = arguments.getString("requiredDependencies").orEmpty()
            .split(',')
            .map(String::trim)
            .filter { Regex("""^[A-Za-z_$][A-Za-z0-9_$]{0,127}$""").matches(it) }
            .take(8)
            .toSet()
        var markerCandidateName: String? = null
        val locatorDiagnostics: (String) -> Unit = { detail ->
            // This opt-in probe reports structural metadata only; never print player source or URLs.
            System.out.println("liveLocator; $detail")
            markerCandidateName = Regex("""(?:^|; )marker=([A-Za-z_$][A-Za-z0-9_$]{0,127})""")
                .find(detail)?.groupValues?.getOrNull(1) ?: markerCandidateName
        }
        val executable = YoutubePlayerTransformLocator.locateExecutable(
            script.source,
            diagnosticIdentifiers = diagnosticSymbol?.let(::setOf) ?: emptySet(),
            requiredDependencies = requiredDependencies,
            onDiagnosticStage = locatorDiagnostics,
        )
        val functions = executable.functions
        val urlProgram = executable.urlThrottlingProgram
        if (arguments.getString("probePathNTransform") == "true") {
            val syntheticUrl = "https://r1.googlevideo.com/videoplayback/n/LowTube_old_segment/chunk?expire=2000000000&n=LowTube_new_query"
            val candidates = YoutubePlayerTransformLocator.locateUrlTransformDiagnosticCandidates(script.source)
            val outputs = if (candidates.isEmpty()) emptyList() else runCatching {
                runBlocking {
                    YoutubePlayerJavaScriptRuntime().executeFullPlayerFunctions(
                        script.source,
                        candidates.map { YoutubePlayerJavaScriptRuntime.FunctionCall(it.name, syntheticUrl) },
                    )
                }
            }.getOrNull().orEmpty()
            candidates.forEachIndexed { index, candidate ->
                val output = outputs.getOrNull(index)
                val inputUri = URI(syntheticUrl)
                val outputUri = output?.let { runCatching { URI(it) }.getOrNull() }
                val inputPath = inputUri.rawPath.orEmpty().split('/')
                val outputPath = outputUri?.rawPath.orEmpty().split('/')
                val sameTrustedOrigin = outputUri != null &&
                    outputUri.scheme.equals(inputUri.scheme, ignoreCase = true) &&
                    outputUri.host.equals(inputUri.host, ignoreCase = true) &&
                    outputUri.port == inputUri.port &&
                    outputUri.rawUserInfo == null
                val pathSegmentRewritten = inputPath.size == outputPath.size && inputPath.size > 4 &&
                    inputPath.take(3) == outputPath.take(3) &&
                    inputPath[3] == "LowTube_old_segment" && outputPath[3] != inputPath[3] &&
                    inputPath.drop(4) == outputPath.drop(4)
                val outputN = outputUri?.let { queryValue(it, "n") }
                val productionSafetyAccepted = output?.let {
                    YoutubePlayerUrlTransformer.acceptUrlNTransform(syntheticUrl, it) != null
                } == true
                System.out.println(
                    "livePathNTransformCandidate; name=${candidate.name}; executed=${output != null}; " +
                        "sameInput=${output == syntheticUrl}; sameTrustedOrigin=$sameTrustedOrigin; " +
                        "outputPathSegments=${outputPath.size}; outputPathMask=${outputPath.joinToString("/") { if (it.contains("LowTube")) "<probe>" else it.take(48) }}; " +
                        "outputQueryKeys=${outputUri?.rawQuery.orEmpty().split('&').map { it.substringBefore('=') }.filter(String::isNotBlank).joinToString(",")}; " +
                        "pathSegmentRewritten=$pathSegmentRewritten; queryUnchanged=${outputUri?.rawQuery == inputUri.rawQuery}; " +
                        "outputHasQuery=${outputUri?.rawQuery != null}; outputNIsNew=${outputN == "LowTube_new_query"}; " +
                        "productionSafetyAccepted=$productionSafetyAccepted; outputChars=${output?.length ?: 0}",
                )
            }
            System.out.println("livePathNTransformCandidateSummary; count=${candidates.size}; processPssKiB=${Debug.getPss()}")
            return
        }
        if (arguments.getString("probeRawUrlCandidates") == "true") {
            val syntheticUrl = "https://r1.googlevideo.com/videoplayback/n/LowTube_old_segment/chunk?expire=2000000000&itag=18&n=LowTube_new_query"
            val runtime = YoutubePlayerJavaScriptRuntime()
            val candidates = YoutubePlayerTransformLocator.locateUrlTransformDiagnosticCandidates(script.source)
            candidates.forEach { candidate ->
                val requiredDependencies = LinkedHashSet<String>()
                var activeCandidate = candidate
                var transformed: String? = null
                var failure: Throwable? = null
                for (attempt in 0..MAX_RAW_URL_CANDIDATE_RECOVERIES) {
                    val execution = runCatching {
                        runBlocking { runtime.executeFunction(activeCandidate.program, activeCandidate.name, syntheticUrl) }
                    }
                    transformed = execution.getOrNull()
                    failure = execution.exceptionOrNull()
                    if (transformed != null) break
                    val unresolved = failure?.let { YoutubePlayerJavaScriptRuntime.unresolvedIdentifier(it) } ?: break
                    if (!requiredDependencies.add(unresolved) || requiredDependencies.size > MAX_RAW_URL_CANDIDATE_RECOVERIES) break
                    val rebuilt = runCatching {
                        YoutubePlayerTransformLocator.locateUrlTransformDiagnosticCandidates(
                            script.source,
                            requiredDependencies = requiredDependencies,
                        ).firstOrNull { it.name == candidate.name }
                    }.getOrNull() ?: break
                    activeCandidate = rebuilt
                }
                val accepted = transformed?.let {
                    YoutubePlayerUrlTransformer.acceptUrlNTransform(syntheticUrl, it) != null
                } == true
                val reason = failure?.let { error ->
                    ((error.cause ?: error).message.orEmpty())
                        .replace(Regex("[^A-Za-z0-9 _-]"), " ").take(384)
                }.orEmpty()
                System.out.println(
                    "liveRawUrlTransformCandidate; name=${candidate.name}; programChars=${activeCandidate.program.length}; " +
                        "recoveredDependencies=${requiredDependencies.size}; dependencyNames=${requiredDependencies.joinToString(",")}; " +
                        "executed=${transformed != null}; " +
                        "changedNWithSameHostPath=$accepted; outputChars=${transformed?.length ?: 0}; " +
                        "failure=${failure?.javaClass?.simpleName.orEmpty()}; reason=$reason",
                )
            }
            val productionPathFunction = functions.urlPathThrottling
            val fullFunctionAttempt = runCatching {
                runBlocking {
                    if (productionPathFunction == null) emptyList()
                    else runtime.executeFullPlayerFunctions(
                        script.source,
                        listOf(YoutubePlayerJavaScriptRuntime.FunctionCall(productionPathFunction, syntheticUrl)),
                    )
                }
            }
            val fullFunctionOutputs = fullFunctionAttempt.getOrNull().orEmpty()
            val acceptedFunctionIndex = fullFunctionOutputs.indices.firstOrNull { index ->
                fullFunctionOutputs.getOrNull(index)?.let {
                    YoutubePlayerUrlTransformer.acceptUrlNTransform(syntheticUrl, it) != null
                } == true
            }
            val fullFunctionOutput = acceptedFunctionIndex?.let(fullFunctionOutputs::get)
            val fullFunctionAccepted = fullFunctionOutput?.let {
                YoutubePlayerUrlTransformer.acceptUrlNTransform(syntheticUrl, it) != null
            } == true
            val inputUri = URI(syntheticUrl)
            val outputUri = fullFunctionOutput?.let { runCatching { URI(it) }.getOrNull() }
            val sameTrustedOrigin = outputUri != null &&
                outputUri.scheme.equals(inputUri.scheme, ignoreCase = true) &&
                outputUri.host.equals(inputUri.host, ignoreCase = true) &&
                outputUri.port == inputUri.port && outputUri.rawUserInfo == null
            val inputPath = inputUri.rawPath.orEmpty().split('/')
            val outputPath = outputUri?.rawPath.orEmpty().split('/')
            val pathSegmentRewritten = inputPath.size == outputPath.size && inputPath.size > 4 &&
                inputPath.take(3) == outputPath.take(3) &&
                inputPath[3] == "LowTube_old_segment" && outputPath[3] != inputPath[3] &&
                inputPath.drop(4) == outputPath.drop(4)
            val queryUnchanged = outputUri?.rawQuery == inputUri.rawQuery
            if (arguments.getString("acceptPathNTransform") == "true") {
                assertTrue(
                    "Full player bundle must rewrite only the n path segment with the production safety gate; " +
                        "productionCandidate=$productionPathFunction",
                    fullFunctionAccepted && sameTrustedOrigin && queryUnchanged && pathSegmentRewritten,
                )
            }
            val fullFunctionFailure = fullFunctionAttempt.exceptionOrNull()?.let { error ->
                ((error.cause ?: error).message.orEmpty())
                    .replace(Regex("[^A-Za-z0-9 _-]"), " ").take(256)
            }.orEmpty()
            System.out.println(
                "liveFullFunctionUrlNTransform; candidateNames=$productionPathFunction; " +
                    "acceptedCandidate=${if (acceptedFunctionIndex != null) productionPathFunction.orEmpty() else ""}; " +
                    "executed=${fullFunctionOutputs.size}; safetyAccepted=$fullFunctionAccepted; " +
                    "sameTrustedOrigin=$sameTrustedOrigin; queryUnchanged=$queryUnchanged; pathSegmentRewritten=$pathSegmentRewritten; " +
                    "failureType=${fullFunctionAttempt.exceptionOrNull()?.javaClass?.simpleName.orEmpty()}; failure=$fullFunctionFailure",
            )
            System.out.println("liveRawUrlTransformCandidateSummary; count=${candidates.size}")
            return
        }
        if (functions.signature == null && arguments.getString("nLocatorOnly") != "true") {
            val candidateStartedAt = System.nanoTime()
            val candidates = YoutubePlayerTransformLocator.locateExecutable(
                script.source,
                onDiagnosticStage = locatorDiagnostics,
                includeMarkerCandidates = true,
            ).markerCandidates.take(MAX_MARKER_SIGNATURE_PROBES)
            val stringTransformNames = YoutubePlayerTransformLocator.findStringOperationCandidates(
                script.source,
                MAX_STRING_OPERATION_PROBES,
                locatorDiagnostics,
            )
            val runtime = YoutubePlayerJavaScriptRuntime()
            // Match the signature-spec probe used by the current upstream extraction model:
            // distinct code units 0..N-1 make swaps, reversals and removals observable without
            // revealing any stream URL or relying on guessed printable test strings.
            val inputs = listOf(64, 88, 112).map(::signatureSpecProbeInput)
            val candidateNames = (candidates.map { it.name } + stringTransformNames).distinct().take(MAX_TOTAL_SIGNATURE_PROBES)
            val asyncNames = candidates.filter { it.isAsync }.mapTo(HashSet()) { it.name }
            val batchNames = candidateNames.filterNot { it in asyncNames }
            val calls = batchNames.flatMap { name ->
                inputs.map { input -> YoutubePlayerJavaScriptRuntime.FunctionCall(name, input) }
            }
            val outputs = if (calls.isNotEmpty()) runCatching {
                runBlocking { runtime.executeFullPlayerFunctions(script.source, calls) }
            }.getOrNull().orEmpty() else emptyList()
            val candidateResults = calls.zip(outputs).groupBy({ it.first.functionName }, { it.second })
            candidateNames.forEach { name ->
                val candidate = candidates.firstOrNull { it.name == name }
                val candidateOutputs = candidateResults[name].orEmpty()
                System.out.println(
                    "liveSignatureTransformProbe; candidate=$name; " +
                        "kind=${if (name in stringTransformNames) "string_operation" else "marker"}; async=${candidate?.isAsync == true}; " +
                        "programChars=${candidate?.program?.length ?: 0}; inputCount=${inputs.size}; " +
                        "outputCount=${candidateOutputs.count { !it.isNullOrBlank() }}; " +
                        "changed=${candidateOutputs.zip(inputs).count { (output, input) -> !output.isNullOrBlank() && output != input }}; " +
                        "distinctOutputs=${candidateOutputs.filterNotNull().distinct().size}; " +
                        "sameLengthAsInput=${candidateOutputs.zip(inputs).count { (output, input) -> output?.length == input.length }}; " +
                        "inputAlphabetOnly=${candidateOutputs.zip(inputs).count { (output, input) -> output != null && output.all(input::contains) }}; " +
                        "permutation=${candidateOutputs.zip(inputs).count { (output, input) -> output != null && output.length == input.length && output.groupingBy { it }.eachCount() == input.groupingBy { it }.eachCount() }}; " +
                        "objectStringOutputs=${candidateOutputs.count { it == "[object Object]" }}; " +
                        "outputChars=${candidateOutputs.joinToString(",") { it?.length?.toString() ?: "0" }}",
                )
            }
            System.out.println(
                "liveSignatureTransformProbeSummary; markerCandidates=${candidates.size}; " +
                    "stringOperationCandidates=${stringTransformNames.size}; candidates=${candidateNames.size}; " +
                    "batchCalls=${calls.size}; outputCount=${outputs.count { !it.isNullOrBlank() }}; " +
                    "wallMs=${(System.nanoTime() - candidateStartedAt) / 1_000_000L}; processPssKiB=${Debug.getPss()}",
            )
        }
        logStage(
            "locator",
            locatorStartedAt,
            locatorCpuStarted,
            locatorPssStarted,
            script.source.length,
            "signature=${functions.signature != null}; n=${functions.throttling != null}; " +
                "urlN=${functions.urlThrottling != null}; programChars=${urlProgram?.length ?: 0}",
        )
        if (arguments.getString("nLocatorOnly") == "true") {
            System.out.println("livePlayerDiagnostic=n_locator_only; signature_probes=skipped; url_execution=skipped")
            return
        }
        if (arguments.getString("signatureProbeOnly") == "true") {
            System.out.println("livePlayerDiagnostic=signature_probe_only; downstream n/url execution skipped")
            return
        }

        if (functions.urlThrottling == null || urlProgram.isNullOrBlank()) {
            System.out.println(
                "liveUrlNTransform; outcome=not_found; wallMs=0; cpuMs=0; " +
                    "processPssKiB=${Debug.getPss()};",
            )
        } else {
            val sampleN = "LowTube_n_probe_Aa0123456789"
            val sampleUrl = "https://r1.googlevideo.com/videoplayback?expire=2000000000&itag=18&n=$sampleN"
            val runtimeStartedAt = System.nanoTime()
            val runtimeCpuStarted = Process.getElapsedCpuTime()
            val runtimePssStarted = Debug.getPss()
            val runtime = YoutubePlayerJavaScriptRuntime()
            var activeExecutable = executable
            val recoveredDependencies = LinkedHashSet(requiredDependencies)
            var output: String? = null
            var failure: Throwable? = null
            var lastExecutionProgram = ""
            for (attempt in 0..MAX_RESOLVER_RECOVERIES) {
                val activeFunction = activeExecutable.functions.urlThrottling ?: break
                val activeProgram = activeExecutable.urlThrottlingProgram?.takeIf(String::isNotBlank) ?: break
                val execution = runCatching {
                    runBlocking {
                        val executeFullPlayer = arguments.getString("executeFullPlayerScript") == "true"
                        if (executeFullPlayer) {
                            val probeValues = listOf(
                                "LowTube_n_probe_Aa0123456789",
                                "abcdefghijklmnopqrstuvwxyz0123456789",
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "A5g1S3m9Q2v8K4p6R0x7W1z5",
                            )
                            val batch = runtime.executeFullPlayerFunctions(
                                script.source,
                                probeValues.map { value ->
                                    val input = Regex("([?&]n=)[^&]*").replace(sampleUrl) { match ->
                                        match.groupValues[1] + value
                                    }
                                    YoutubePlayerJavaScriptRuntime.FunctionCall(activeFunction, input)
                                },
                            )
                            JSONArray().apply { batch.forEach { put(it ?: JSONObject.NULL) } }.toString()
                        } else {
                            lastExecutionProgram = activeProgram
                            runtime.executeFunction(activeProgram, activeFunction, sampleUrl)
                        }
                    }
                }
                output = execution.getOrNull()
                failure = execution.exceptionOrNull()
                if (arguments.getString("executeFullPlayerScript") == "true") break
                if (output != null) break
                val unresolved = failure?.let { YoutubePlayerJavaScriptRuntime.unresolvedIdentifier(it) } ?: break
                if (!recoveredDependencies.add(unresolved) ||
                    recoveredDependencies.size > MAX_RESOLVER_RECOVERIES
                ) break
                val rebuild = runCatching {
                    YoutubePlayerTransformLocator.locateExecutable(
                        script.source,
                        diagnosticIdentifiers = diagnosticSymbol?.let(::setOf) ?: emptySet(),
                        requiredDependencies = recoveredDependencies,
                        onDiagnosticStage = locatorDiagnostics,
                    )
                }
                if (rebuild.isFailure) {
                    failure = rebuild.exceptionOrNull()
                    System.out.println(
                        "liveUrlNRecoveryRejected; missing=$unresolved; " +
                            "failure=${failure?.javaClass?.simpleName}; " +
                            "reason=${failure?.message.orEmpty().replace(Regex("[^A-Za-z0-9 _-]"), " ").take(96)}",
                    )
                    break
                }
                val nextExecutable = rebuild.getOrNull() ?: break
                if (nextExecutable.urlThrottlingProgram == activeProgram) break
                activeExecutable = nextExecutable
                System.out.println(
                    "liveUrlNRecovery; added=$unresolved; roots=" + recoveredDependencies.size + "; " +
                        "programChars=" + (activeExecutable.urlThrottlingProgram?.length ?: 0),
                )
            }
            if (failure != null && !markerCandidateName.isNullOrBlank()) {
                val candidateStartedAt = System.nanoTime()
                val candidateCpuStarted = Process.getElapsedCpuTime()
                val candidatePssStarted = Debug.getPss()
                val candidate = YoutubePlayerTransformLocator.locateMarkerCandidate(
                    script.source,
                    markerCandidateName.orEmpty(),
                    onDiagnosticStage = { detail -> System.out.println("liveMarkerLocator; $detail") },
                )
                if (candidate == null) {
                    System.out.println(
                        "liveMarkerCandidate; outcome=not_found; name=$markerCandidateName; " +
                            "wallMs=${(System.nanoTime() - candidateStartedAt) / 1_000_000L}; " +
                            "cpuMs=${Process.getElapsedCpuTime() - candidateCpuStarted}; " +
                            "processPssKiB=$candidatePssStarted->${Debug.getPss()}",
                    )
                } else if (candidate.isAsync) {
                    System.out.println(
                        "liveMarkerCandidate; outcome=async_not_executed; name=${candidate.name}; " +
                            "programChars=${candidate.program.length}; " +
                            "wallMs=${(System.nanoTime() - candidateStartedAt) / 1_000_000L}; " +
                            "cpuMs=${Process.getElapsedCpuTime() - candidateCpuStarted}; " +
                            "processPssKiB=$candidatePssStarted->${Debug.getPss()}",
                    )
                } else {
                    val candidateExecution = runCatching {
                        runBlocking { runtime.inspectUrlCandidate(candidate.program, candidate.name) }
                    }
                    val candidateJson = candidateExecution.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val candidateError = (candidateExecution.exceptionOrNull()?.cause ?: candidateExecution.exceptionOrNull())
                        ?.message.orEmpty().replace(Regex("[^A-Za-z0-9 _-]"), " ").take(100)
                    val candidateResponseError = candidateJson?.optString("error").orEmpty()
                        .replace(Regex("[^A-Za-z0-9 _-]"), " ").take(100)
                    val candidateStack = candidateJson?.optString("stack").orEmpty()
                        .replace(Regex("[^A-Za-z0-9 _-]"), " ").take(160)
                    val rawCandidateStack = candidateJson?.optString("stack").orEmpty()
                    val failureLine = Regex("lowtube-player-runtime\\.js:(\\d+):\\d+")
                        .find(rawCandidateStack)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val failureLineIdentifiers = failureLine?.let { runtimeLine ->
                        candidate.program.lineSequence().drop((runtimeLine - 2).coerceAtLeast(0)).firstOrNull()
                            ?.let { line -> Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}").findAll(line).map { it.value }.take(16).toList() }
                    }.orEmpty().joinToString(",")
                    val missingMarkerSymbol = Regex("""ReferenceError\s+([A-Za-z_$][A-Za-z0-9_$]{0,127})\s+is not defined""")
                        .find(candidateError)?.groupValues?.getOrNull(1)
                    if (missingMarkerSymbol != null) {
                        YoutubePlayerTransformLocator.locateMarkerCandidate(
                            script.source,
                            candidate.name,
                            onDiagnosticStage = { detail -> System.out.println("liveMarkerTargetAudit; $detail") },
                            diagnosticIdentifiers = setOf(missingMarkerSymbol),
                        )
                    } else if (candidateJson?.optBoolean("ok") == false) {
                        val targetSymbols = failureLineIdentifiers.split(',')
                            .filter { it.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,127}$")) }
                            .filterNot { it in setOf("g", "class", "constructor", "this") }
                            .take(3)
                            .toSet()
                        if (targetSymbols.isNotEmpty()) {
                            YoutubePlayerTransformLocator.locateMarkerCandidate(
                                script.source,
                                candidate.name,
                                onDiagnosticStage = { detail -> System.out.println("liveMarkerTargetAudit; $detail") },
                                diagnosticIdentifiers = targetSymbols,
                            )
                        }
                    }
                    val methods = candidateJson?.optString("methods").orEmpty().split(',').filter(String::isNotBlank)
                    val safeTrigger = candidateJson?.optString("trigger").orEmpty()
                        .takeIf { Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,127}$").matches(it) }.orEmpty()
                    val triggerError = candidateJson?.optString("triggerError").orEmpty()
                        .replace(Regex("[^A-Za-z0-9 _-]"), " ").take(80)
                    System.out.println(
                        "liveMarkerCandidate; outcome=${if (candidateJson?.optBoolean("ok") == true) "inspected" else "failed"}; " +
                            "name=${candidate.name}; programChars=${candidate.program.length}; " +
                            "signatureChanged=${candidateJson?.optString("signature") != "LowTube_signature_probe"}; " +
                            "nChanged=${candidateJson?.optString("n") != "LowTube_n_probe"}; trigger=$safeTrigger; " +
                        "methodCount=${methods.size}; triggerError=$triggerError; " +
                        "stack=$candidateStack; failureLine=$failureLine; lineIds=$failureLineIdentifiers; " +
                        "error=${candidateResponseError.ifBlank { candidateError }}; " +
                            "wallMs=${(System.nanoTime() - candidateStartedAt) / 1_000_000L}; " +
                            "cpuMs=${Process.getElapsedCpuTime() - candidateCpuStarted}; " +
                            "processPssKiB=$candidatePssStarted->${Debug.getPss()}",
                    )
                }
            }
            val runtimeCpuMillis = Process.getElapsedCpuTime() - runtimeCpuStarted
            val runtimeWallMillis = (System.nanoTime() - runtimeStartedAt) / 1_000_000L
            val runtimePssEnded = Debug.getPss()
            val fullBundleOutputs = if (arguments.getString("executeFullPlayerScript") == "true") {
                output?.let {
                    runCatching {
                        JSONArray(it).let { values -> List(values.length()) { index -> values.optString(index).takeIf(String::isNotBlank) } }
                    }.getOrNull()
                }
            } else null
            val candidateOutputs = (fullBundleOutputs ?: listOfNotNull(output)).filterNotNull()
            val acceptedCandidates = candidateOutputs.count {
                YoutubePlayerUrlTransformer.acceptUrlNTransform(sampleUrl, it) != null
            }
            val outcome = when {
                output == null -> "runtime_failed"
                acceptedCandidates > 0 ->
                    "accepted_changed_n_same_host_path"
                else -> "output_rejected_by_url_safety_checks"
            }
            val safeUrlShape = if (fullBundleOutputs != null) {
                "probeCount=${fullBundleOutputs.size}; validChangedN=$acceptedCandidates; " +
                    "outputChars=${fullBundleOutputs.joinToString(",") { it?.length?.toString() ?: "0" }}"
            } else {
                val inputUri = URI(sampleUrl)
                val outputUri = output?.let { runCatching { URI(it) }.getOrNull() }
                val inputN = queryValue(inputUri, "n")
                val outputN = outputUri?.let { queryValue(it, "n") }
                "outputUri=${outputUri != null}; " +
                    "sameScheme=${outputUri?.scheme.equals(inputUri.scheme, ignoreCase = true)}; " +
                    "sameHost=${outputUri?.host.equals(inputUri.host, ignoreCase = true)}; " +
                    "samePort=${outputUri?.port == inputUri.port}; " +
                    "samePath=${outputUri?.rawPath == inputUri.rawPath}; " +
                    "nPresent=${!outputN.isNullOrBlank()}; nChanged=${!outputN.isNullOrBlank() && outputN != inputN}; " +
                    "outputChars=${output?.length ?: 0}"
            }
            val safeFailure = failure?.message.orEmpty()
                .replace(Regex("[^A-Za-z0-9 _-]"), " ").take(120)
            val rootCause = failure?.cause ?: failure
            val safeCause = rootCause?.message.orEmpty()
                .replace(Regex("[^A-Za-z0-9 _-]"), " ").take(120)
            val unresolvedIdentifier = failure?.let { YoutubePlayerJavaScriptRuntime.unresolvedIdentifier(it) }.orEmpty()
            val runtimeLocation = Regex("""lowtube-player-runtime\.js:(\d+):(\d+)""")
                .find(rootCause?.message.orEmpty())
            val runtimeLine = runtimeLocation?.groupValues?.getOrNull(1)?.toIntOrNull()
            val runtimeColumn = runtimeLocation?.groupValues?.getOrNull(2)?.toIntOrNull()
            val failingProgramLine = runtimeLine?.let { line ->
                lastExecutionProgram.lineSequence().drop((line - 1).coerceAtLeast(0)).firstOrNull()
            }.orEmpty()
            val failingContext = runtimeColumn?.let { column ->
                val center = (column - 1).coerceIn(0, failingProgramLine.length)
                failingProgramLine.substring((center - 48).coerceAtLeast(0), (center + 49).coerceAtMost(failingProgramLine.length))
            }.orEmpty()
            val lineIdentifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}").findAll(failingContext)
                .map { it.value }.distinct().take(16).toList()
            val failingOwner = Regex("""at\s+([A-Za-z_$][A-Za-z0-9_$]{0,127})\s*\(lowtube-player-runtime\.js""")
                .find(rootCause?.message.orEmpty())?.groupValues?.getOrNull(1).orEmpty()
            System.out.println(
                "liveUrlNFailureCause; type=" + rootCause?.javaClass?.simpleName.orEmpty() +
                    "; cause=" + safeCause + "; unresolved=" + unresolvedIdentifier,
            )
            System.out.println(
                "liveUrlNFailureContext; owner=$failingOwner; line=$runtimeLine; column=$runtimeColumn; " +
                    "lineIds=" + lineIdentifiers.joinToString(","),
            )
            System.out.println(
                "liveUrlNTransform; outcome=$outcome; wallMs=$runtimeWallMillis; cpuMs=$runtimeCpuMillis; " +
                    "$safeUrlShape; failureType=${failure?.javaClass?.simpleName.orEmpty()}; failure=$safeFailure; " +
                    "processPssKiB=$runtimePssStarted->$runtimePssEnded",
            )
        }

        val scriptVersion = script.url.substringAfter("/s/player/").substringBefore("/base.js")
            .replace(Regex("[^A-Za-z0-9_./-]"), "")
        System.out.println(
            "livePlayerDiagnostic; version=$scriptVersion; sourceChars=${script.source.length}; " +
                "totalWallMs=${(System.nanoTime() - diagnosticStartedAt) / 1_000_000L}; processPssKiB=${Debug.getPss()}",
        )
    }

    private fun readBounded(url: String, maxBytes: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", YoutubePlayerScriptCache.USER_AGENT)
            setRequestProperty("Accept", "text/html,application/javascript,*/*;q=0.8")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Public player diagnostic request failed with HTTP $status.")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("Public player diagnostic response exceeded its size limit.")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun queryValue(uri: URI, name: String): String? = uri.rawQuery
        ?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")

    private fun logStage(
        stage: String,
        stageStartedAt: Long,
        cpuStarted: Long,
        pssStartedKiB: Long,
        sourceChars: Int,
        details: String = "",
    ) {
        val suffix = if (details.isBlank()) "" else "; $details"
        System.out.println(
            "liveProbeStage=$stage; wallMs=${(System.nanoTime() - stageStartedAt) / 1_000_000L}; " +
                "cpuMs=${Process.getElapsedCpuTime() - cpuStarted}; " +
                "processPssKiB=$pssStartedKiB->${Debug.getPss()}; sourceChars=$sourceChars$suffix",
        )
    }

    companion object {
        private const val TIMEOUT_MS = 12_000
        private const val MAX_WATCH_BYTES = 8 * 1024 * 1024
        private const val MAX_RESOLVER_RECOVERIES = 8
        private const val MAX_MARKER_SIGNATURE_PROBES = 12
        private const val MAX_STRING_OPERATION_PROBES = 30
        private const val MAX_TOTAL_SIGNATURE_PROBES = 42
        private const val MAX_RAW_URL_CANDIDATE_RECOVERIES = 4
        private const val DOCUMENT_SHIM = """
            var document={
              createElement:function(){return {style:{},canPlayType:function(){return "";},setAttribute:function(){},getAttribute:function(){return "";},appendChild:function(){},removeChild:function(){},children:[],childNodes:[]};},
              getElementsByTagName:function(){return [];},querySelector:function(){return null;},querySelectorAll:function(){return [];},
              body:{appendChild:function(){},removeChild:function(){},style:{}},
              documentElement:{style:{},classList:{add:function(){},remove:function(){}}},
              addEventListener:function(){},removeEventListener:function(){}
            };
            var navigator={userAgent:"",language:"en",platform:"Android",vendor:""};
            var location={href:"https://www.youtube.com/watch?v=aqz-KE-bpKQ",protocol:"https:",hostname:"www.youtube.com"};
        """
        private const val XML_HTTP_REQUEST_SHIM = """
            function XMLHttpRequest(){this.readyState=4;this.status=0;this.responseText="";this.response=null;this.responseXML=null;}
            XMLHttpRequest.prototype.open=function(){};
            XMLHttpRequest.prototype.send=function(){};
            XMLHttpRequest.prototype.setRequestHeader=function(){};
            XMLHttpRequest.prototype.overrideMimeType=function(){};
            XMLHttpRequest.prototype.abort=function(){};
            XMLHttpRequest.prototype.getResponseHeader=function(){return null;};
            XMLHttpRequest.prototype.getAllResponseHeaders=function(){return "";};
            XMLHttpRequest.prototype.addEventListener=function(){};
            XMLHttpRequest.prototype.removeEventListener=function(){};
        """
        private val PLAYER_SCRIPT_REFERENCE = Regex("""[\"'](?:jsUrl|js|PLAYER_JS_URL)[\"']\s*:\s*[\"']([^\"']*?/s/player/[^\"']+/base\.js)[\"']""")
    }
}

private fun signatureSpecProbeInput(length: Int): String {
    require(length in 1..255)
    return buildString(length) {
        for (codeUnit in 0 until length) append(codeUnit.toChar())
    }
}
