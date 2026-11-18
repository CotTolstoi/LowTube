package com.lowtube.android.data

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Executes a bounded player transform in a disposable QuickJS runtime with no host bindings. */
internal class YoutubePlayerJavaScriptRuntime {
    data class FunctionCall(
        val functionName: String,
        val input: String,
        val argumentPrefix: String? = null,
    )
    data class UrlObjectNTransformResult(
        val url: String?,
        val candidateFound: Boolean,
        val methodCount: Int,
        val nChanged: Boolean,
    )

    suspend fun inspectUrlCandidate(
        source: String,
        functionName: String,
        timeoutMillis: Long = 5_000L,
        inputUrl: String = "https://youtube.com/watch?v=lowtube-probe&n=LowTube_n_probe",
    ): String {
        require(FUNCTION_NAME.matches(functionName)) { "Invalid YouTube player function name." }
        require(source.isNotBlank() && source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
            "YouTube player transform is empty or larger than 8 MB."
        }
        require(timeoutMillis in 250L..MAX_CANDIDATE_TIMEOUT_MS) { "The player candidate timeout is invalid." }
        require(inputUrl.isNotBlank() && inputUrl.length <= MAX_INPUT_LENGTH) { "The player candidate URL is invalid." }

        val preparedSource = YoutubePlayerScriptScopeNormalizer.prepare(source)
        val program = buildUrlCandidateProgram(preparedSource, functionName, inputUrl)
        return withContext(Dispatchers.Default) {
            val quickJs = QuickJs.create(Dispatchers.Default)
            try {
                quickJs.memoryLimit = MAX_RUNTIME_MEMORY_BYTES
                quickJs.maxStackSize = MAX_STACK_SIZE_BYTES
                quickJs.evaluationTimeoutMillis = timeoutMillis
                withTimeout(timeoutMillis + CANCELLATION_GRACE_MS) {
                    quickJs.evaluate<String>(program, filename = RUNTIME_FILENAME).also {
                        if (it.length > MAX_CANDIDATE_OUTPUT_LENGTH) {
                            throw IOException("YouTube player candidate returned an oversized result.")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("YouTube player candidate timed out.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuickJsInterruptedException) {
                throw IOException("YouTube player candidate timed out.", e)
            } catch (e: QuickJsException) {
                val location = e.lineNumber?.let { " at line $it" }.orEmpty()
                throw IOException("YouTube player candidate could not be evaluated$location.", e)
            } finally {
                quickJs.close()
            }
        }
    }

    suspend fun executeFunction(
        source: String,
        functionName: String,
        input: String,
        argumentPrefix: String? = null,
    ): String {
        require(FUNCTION_NAME.matches(functionName)) { "Invalid YouTube player function name." }
        require(source.isNotBlank() && source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
            "YouTube player transform is empty or larger than 8 MB."
        }
        require(input.length <= MAX_INPUT_LENGTH) { "YouTube player challenge is too large." }
        require(argumentPrefix == null || EXTRA_ARGUMENT_PREFIX.matches(argumentPrefix)) {
            "YouTube player function arguments are invalid."
        }

        val preparedSource = YoutubePlayerScriptScopeNormalizer.prepare(source)
        val program = buildProgram(preparedSource, functionName, input, argumentPrefix.orEmpty())
        return withContext(Dispatchers.Default) {
            val quickJs = QuickJs.create(Dispatchers.Default)
            try {
                quickJs.memoryLimit = MAX_RUNTIME_MEMORY_BYTES
                quickJs.maxStackSize = MAX_STACK_SIZE_BYTES
                quickJs.evaluationTimeoutMillis = EXECUTION_TIMEOUT_MS
                withTimeout(EXECUTION_TIMEOUT_MS + CANCELLATION_GRACE_MS) {
                    val result = quickJs.evaluate<String>(program, filename = RUNTIME_FILENAME)
                    if (result.isBlank() || result.length > MAX_INPUT_LENGTH) {
                        throw IOException("YouTube player transform returned an invalid result.")
                    }
                    result
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("YouTube player transform timed out.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuickJsInterruptedException) {
                throw IOException("YouTube player transform timed out.", e)
            } catch (e: QuickJsException) {
                val location = e.lineNumber?.let { " at line $it" }.orEmpty()
                throw IOException("YouTube player transform could not be evaluated$location.", e)
            } finally {
                quickJs.close()
            }
        }
    }

    /** Applies a bounded, marker-selected URL-object n mutator in one isolated player-bundle evaluation. */
    suspend fun executeFullPlayerUrlObjectNTransforms(
        source: String,
        candidateNames: List<String>,
        urls: List<String>,
    ): List<UrlObjectNTransformResult> {
        require(source.isNotBlank() && source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
            "YouTube player script is empty or larger than 8 MB."
        }
        require(candidateNames.isNotEmpty() && candidateNames.size <= MAX_MARKER_URL_CANDIDATES &&
            candidateNames.all(FUNCTION_NAME::matches)
        ) { "YouTube player URL-object candidates are invalid." }
        require(urls.isNotEmpty() && urls.size <= MAX_MARKER_URL_BATCH &&
            urls.all { it.isNotBlank() && it.length <= MAX_INPUT_LENGTH }
        ) { "YouTube player URL-object batch is invalid." }

        val program = buildFullUrlObjectNProgram(source, candidateNames, urls)
        return withContext(Dispatchers.Default) {
            val quickJs = QuickJs.create(Dispatchers.Default)
            try {
                quickJs.memoryLimit = MAX_RUNTIME_MEMORY_BYTES
                quickJs.maxStackSize = MAX_STACK_SIZE_BYTES
                quickJs.evaluationTimeoutMillis = EXECUTION_TIMEOUT_MS
                val encoded = withTimeout(EXECUTION_TIMEOUT_MS + CANCELLATION_GRACE_MS) {
                    quickJs.evaluate<String>(program, filename = RUNTIME_FILENAME)
                }
                if (encoded.length > MAX_BATCH_OUTPUT_LENGTH) throw IOException("YouTube URL-object output is too large.")
                val results = JSONArray(encoded)
                if (results.length() != urls.size) throw IOException("YouTube player returned an invalid URL-object batch.")
                List(results.length()) { index ->
                    val result = results.optJSONObject(index)
                        ?: return@List UrlObjectNTransformResult(null, false, 0, false)
                    UrlObjectNTransformResult(
                        url = result.optString("url").takeIf { it.isNotBlank() && it.length <= MAX_INPUT_LENGTH },
                        candidateFound = result.optBoolean("candidateFound"),
                        methodCount = result.optInt("methodCount").coerceIn(0, 128),
                        nChanged = result.optBoolean("nChanged"),
                    )
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("YouTube player URL-object transform timed out.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuickJsInterruptedException) {
                throw IOException("YouTube player URL-object transform timed out.", e)
            } catch (e: QuickJsException) {
                val location = e.lineNumber?.let { " at line $it" }.orEmpty()
                throw IOException("YouTube player URL-object transform could not be evaluated$location.", e)
            } finally {
                quickJs.close()
            }
        }
    }

    /** Executes a bounded group of transforms against one full player bundle evaluation. */
    suspend fun executeFullPlayerFunctions(
        source: String,
        calls: List<FunctionCall>,
    ): List<String?> {
        require(source.isNotBlank() && source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
            "YouTube player script is empty or larger than 8 MB."
        }
        require(calls.isNotEmpty() && calls.size <= MAX_BATCH_CALLS) { "The player transform batch is invalid." }
        calls.forEach { call ->
            require(FUNCTION_NAME.matches(call.functionName)) { "Invalid YouTube player function name." }
            require(call.input.length <= MAX_INPUT_LENGTH) { "YouTube player challenge is too large." }
            require(call.argumentPrefix == null || EXTRA_ARGUMENT_PREFIX.matches(call.argumentPrefix)) {
                "YouTube player function arguments are invalid."
            }
        }
        val program = buildFullPlayerBatchProgram(source, calls)
        return withContext(Dispatchers.Default) {
            val quickJs = QuickJs.create(Dispatchers.Default)
            try {
                quickJs.memoryLimit = MAX_RUNTIME_MEMORY_BYTES
                quickJs.maxStackSize = MAX_STACK_SIZE_BYTES
                quickJs.evaluationTimeoutMillis = EXECUTION_TIMEOUT_MS
                val encoded = withTimeout(EXECUTION_TIMEOUT_MS + CANCELLATION_GRACE_MS) {
                    quickJs.evaluate<String>(program, filename = RUNTIME_FILENAME)
                }
                if (encoded.length > MAX_BATCH_OUTPUT_LENGTH) throw IOException("YouTube player batch output is too large.")
                val results = JSONArray(encoded)
                if (results.length() != calls.size) throw IOException("YouTube player returned an invalid batch result.")
                List(results.length()) { index ->
                    val result = results.optJSONObject(index) ?: return@List null
                    if (!result.optBoolean("ok")) return@List null
                    result.optString("value").takeIf { it.isNotBlank() && it.length <= MAX_INPUT_LENGTH }
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("YouTube player transform timed out.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuickJsInterruptedException) {
                throw IOException("YouTube player transform timed out.", e)
            } catch (e: QuickJsException) {
                val location = e.lineNumber?.let { " at line $it" }.orEmpty()
                throw IOException("YouTube player transform could not be evaluated$location.", e)
            } finally {
                quickJs.close()
            }
        }
    }

    private fun buildFullPlayerBatchProgram(source: String, calls: List<FunctionCall>): String = buildString(source.length + 4_096) {
        append(PLAYER_ENVIRONMENT_SHIMS)
        append(YoutubePlayerScriptScopeNormalizer.prepare(source))
        append("\n;var __lowtube_results=[];\n")
        calls.forEachIndexed { index, call ->
            val valueName = "__lowtube_value_$index"
            val errorName = "__lowtube_error_$index"
            append("try{var ").append(valueName).append('=')
                .append(call.functionName).append('(').append(call.argumentPrefix.orEmpty())
                .append(JSONObject.quote(call.input)).append(");")
                .append("__lowtube_results.push({ok:true,value:String(").append(valueName).append(")});")
                .append("}catch(").append(errorName).append("){__lowtube_results.push({ok:false});}\n")
            if (length > MAX_BATCH_PROGRAM_BYTES) throw IOException("YouTube player transform batch is too large.")
        }
        append("JSON.stringify(__lowtube_results);")
    }

    private fun buildProgram(source: String, functionName: String, input: String, argumentPrefix: String): String {
        val resultName = "__lowtube_result_${System.nanoTime().toString(16)}"
        return buildString(source.length + input.length + 192) {
            append("var window=this;var g={};\n")
            append(source)
            append("\n;var ").append(resultName).append('=')
            append(functionName).append('(').append(argumentPrefix).append(JSONObject.quote(input)).append(");\n")
            append("String(").append(resultName).append(");")
        }
    }

    private fun buildUrlCandidateProgram(source: String, functionName: String, inputUrl: String): String = buildString(source.length + 1_200) {
        append(PLAYER_ENVIRONMENT_SHIMS)
        append(source)
        append("\n;var __lowtube_output;try{var __lowtube_url=")
        append(functionName)
        append('(').append(JSONObject.quote(inputUrl)).append(",\"s\",\"\");")
        append("if(!__lowtube_url||typeof __lowtube_url.set!==\"function\"||typeof __lowtube_url.get!==\"function\")throw new Error(\"candidate did not return a mutable URL\");")
        append("var __lowtube_proto=Object.getPrototypeOf(__lowtube_url);var __lowtube_names=__lowtube_proto?Object.getOwnPropertyNames(__lowtube_proto):[];var __lowtube_trigger=\"\";var __lowtube_trigger_error=\"\";")
        append("for(var __lowtube_i=0;__lowtube_i<__lowtube_names.length;__lowtube_i++){var __lowtube_key=__lowtube_names[__lowtube_i];")
        append("if([\"constructor\",\"set\",\"get\",\"clone\"].indexOf(__lowtube_key)>=0||typeof __lowtube_url[__lowtube_key]!==\"function\")continue;")
        append("__lowtube_trigger=__lowtube_key;try{__lowtube_url[__lowtube_key]();}catch(__lowtube_trigger_exception){__lowtube_trigger_error=String(__lowtube_trigger_exception).slice(0,120);}break;}")
        append("var __lowtube_own_names=Object.keys(__lowtube_url).sort();")
        append("__lowtube_output=JSON.stringify({ok:true,signature:__lowtube_url.get(\"s\"),n:__lowtube_url.get(\"n\"),url:String(__lowtube_url.url),innerType:typeof __lowtube_url.url,ownNames:__lowtube_own_names.join(\",\"),trigger:__lowtube_trigger,triggerError:__lowtube_trigger_error,methods:__lowtube_names.join(\",\")});")
        append("}catch(__lowtube_error){__lowtube_output=JSON.stringify({ok:false,error:String(__lowtube_error).slice(0,180),stack:String(__lowtube_error&&__lowtube_error.stack||\"\").slice(0,240)});}String(__lowtube_output);")
    }

    private fun buildFullUrlObjectNProgram(source: String, candidateNames: List<String>, urls: List<String>): String =
        buildString(source.length + urls.size * 256 + 2_048) {
            append(PLAYER_ENVIRONMENT_SHIMS)
            append(YoutubePlayerScriptScopeNormalizer.prepare(source))
            append("\n;var __lowtube_urls=")
            append(JSONArray(urls).toString())
            append(";var __lowtube_results=[];")
            append("for(var __lowtube_u=0;__lowtube_u<__lowtube_urls.length;__lowtube_u++){var __lowtube_accepted=\"\";var __lowtube_found=false;var __lowtube_count=0;var __lowtube_changed=false;")
            append("for(var __lowtube_f=0;__lowtube_f<").append(candidateNames.size).append("&&!__lowtube_accepted;__lowtube_f++){try{var __lowtube_base=null;")
            candidateNames.forEachIndexed { index, name ->
                append("if(__lowtube_f===").append(index).append(")__lowtube_base=")
                append(name).append("(__lowtube_urls[__lowtube_u],\"s\",\"\");")
            }
            append("if(!__lowtube_base||typeof __lowtube_base.get!==\"function\")continue;")
            append("__lowtube_found=true;")
            append("var __lowtube_proto=Object.getPrototypeOf(__lowtube_base);var __lowtube_methods=__lowtube_proto?Object.getOwnPropertyNames(__lowtube_proto):[];")
            append("for(var __lowtube_m=0;__lowtube_m<__lowtube_methods.length&&!__lowtube_accepted;__lowtube_m++){var __lowtube_key=__lowtube_methods[__lowtube_m];")
            append("if([\"constructor\",\"set\",\"get\",\"clone\"].indexOf(__lowtube_key)>=0||typeof __lowtube_base[__lowtube_key]!==\"function\")continue;")
            append("__lowtube_count++;")
            append("try{__lowtube_base[__lowtube_key]();var __lowtube_url=String(__lowtube_base.url);")
            append("if(__lowtube_url!==__lowtube_urls[__lowtube_u]&&__lowtube_url.length<=16384){__lowtube_accepted=__lowtube_url;__lowtube_changed=true;}break;}catch(__lowtube_method_error){}}")
            append("}catch(__lowtube_candidate_error){}}__lowtube_results.push({url:__lowtube_accepted,candidateFound:__lowtube_found,methodCount:__lowtube_count,nChanged:__lowtube_changed});}")
            append("JSON.stringify(__lowtube_results);")
        }

    companion object {
        private val UNRESOLVED_IDENTIFIER = Regex(
            """ReferenceError(?::|\s)\s*['"]?([A-Za-z_$][A-Za-z0-9_$]{0,127})['"]?\s+is not defined""",
        )
        private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        private const val MAX_INPUT_LENGTH = 16 * 1024
        private const val MAX_RUNTIME_MEMORY_BYTES = 96L * 1024 * 1024
        private const val MAX_STACK_SIZE_BYTES = 512L * 1024
        private const val EXECUTION_TIMEOUT_MS = 15_000L
        private const val MAX_CANDIDATE_TIMEOUT_MS = 15_000L
        private const val CANCELLATION_GRACE_MS = 1_000L
        private const val MAX_CANDIDATE_OUTPUT_LENGTH = 20 * 1024
        private const val MAX_BATCH_CALLS = 128
        private const val MAX_MARKER_URL_CANDIDATES = 3
        private const val MAX_MARKER_URL_BATCH = 24
        private const val MAX_BATCH_OUTPUT_LENGTH = 2 * 1024 * 1024
        private const val MAX_BATCH_PROGRAM_BYTES = 10 * 1024 * 1024
        private const val RUNTIME_FILENAME = "lowtube-player-runtime.js"
        private val EXTRA_ARGUMENT_PREFIX = Regex("^(?:[0-9]{1,6},)?$")
        private val FUNCTION_NAME = Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,127}(?:\\.[A-Za-z_$][A-Za-z0-9_$]{0,127}){0,4}$")

        private const val PLAYER_ENVIRONMENT_SHIMS = """
            var window=this;
            var g={};
            var document={
              createElement:function(){return {style:{},canPlayType:function(){return '';},setAttribute:function(){},getAttribute:function(){return '';},appendChild:function(){},removeChild:function(){},children:[],childNodes:[]};},
              getElementsByTagName:function(){return [];},querySelector:function(){return null;},querySelectorAll:function(){return [];},
              body:{appendChild:function(){},removeChild:function(){},style:{}},
              documentElement:{style:{},classList:{add:function(){},remove:function(){}}},
              addEventListener:function(){},removeEventListener:function(){}
            };
            var navigator={userAgent:'',language:'en',platform:'Android',vendor:''};
            var location={href:'https://www.youtube.com/',protocol:'https:',hostname:'www.youtube.com'};
            function XMLHttpRequest(){this.readyState=4;this.status=0;this.responseText='';this.response=null;this.responseXML=null;}
            XMLHttpRequest.prototype.open=function(){};
            XMLHttpRequest.prototype.send=function(){};
            XMLHttpRequest.prototype.setRequestHeader=function(){};
            XMLHttpRequest.prototype.overrideMimeType=function(){};
            XMLHttpRequest.prototype.abort=function(){};
            XMLHttpRequest.prototype.getResponseHeader=function(){return null;};
            XMLHttpRequest.prototype.getAllResponseHeaders=function(){return '';};
            XMLHttpRequest.prototype.addEventListener=function(){};
            XMLHttpRequest.prototype.removeEventListener=function(){};
        """

        internal fun unresolvedIdentifier(failure: Throwable): String? {
            var cause: Throwable? = failure
            val visited = HashSet<Throwable>()
            while (cause != null && visited.add(cause)) {
                UNRESOLVED_IDENTIFIER.find(cause.message.orEmpty())?.groupValues?.getOrNull(1)?.let { return it }
                cause = cause.cause
            }
            return null
        }
    }
}
