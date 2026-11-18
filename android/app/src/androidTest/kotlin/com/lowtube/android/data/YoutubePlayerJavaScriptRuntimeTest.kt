package com.lowtube.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YoutubePlayerJavaScriptRuntimeTest {
    private val runtime by lazy {
        YoutubePlayerJavaScriptRuntime()
    }

    @Test
    fun executesTransformInIsolatedPage() = runBlocking {
        val result = runtime.executeFunction(
            "function transform(s) { return s.split('').reverse().join(''); }",
            "transform",
            "abcdef",
        )

        assertEquals("fedcba", result)
    }

    @Test
    fun runtimeHasNoPageOrHostIoBindings() = runBlocking {
        val result = runtime.executeFunction(
            "function inspect(value) { return typeof window.LowTubeBridge + '|' + typeof window.document + '|' + typeof window.fetch + '|' + typeof window.process + '|' + value; }",
            "inspect",
            "abc",
        )

        assertEquals("undefined|undefined|undefined|undefined|abc", result)
    }

    @Test
    fun executesHelperObjectMethodByDottedName() = runBlocking {
        val result = runtime.executeFunction(
            "var Dec = { swap: function(value) { return value.split('').reverse().join(''); } };",
            "Dec.swap",
            "abcdef",
        )

        assertEquals("fedcba", result)
    }

    @Test
    fun executesArrowFunctionDeclaration() = runBlocking {
        val result = runtime.executeFunction(
            "var throttle = value => value.slice(2);",
            "throttle",
            "abcdef",
        )

        assertEquals("cdef", result)
    }

    @Test
    fun executesOptionalChainingAndNullishCoalescing() = runBlocking {
        val result = runtime.executeFunction(
            "const transforms = { run: (value) => value?.slice(2) ?? 'fallback' };",
            "transforms.run",
            "abcdef",
        )

        assertEquals("cdef", result)
    }

    @Test
    fun executesIsolatedTransformWithExtractedObjectHelper() = runBlocking {
        val source = "var Dec={swap:function(a){return a.split('').reverse().join('')}};function transform(a){return Dec.swap(a)};x.sig||transform(a);"
        val executable = YoutubePlayerTransformLocator.locateExecutable(source)

        val result = runtime.executeFunction(executable.signatureProgram.orEmpty(), "transform", "abcdef")

        assertEquals("fedcba", result)
        assertEquals("transform", executable.functions.signature)
    }

    @Test
    fun executesUrlNTransformWithUniqueDottedModuleAlias() = runBlocking {
        val source = """
            g.Query=function(value){this.value=value;this.get=function(key){var query=this.value.split("?")[1]||"";var parts=query.split("&");for(var i=0;i<parts.length;i++){if(parts[i].indexOf(key+"=")===0)return decodeURIComponent(parts[i].substring(key.length+1))}return ""};this.set=function(key,item){this.value=this.value.replace(/n=[^&]*/,"n="+encodeURIComponent(item))};this.toString=function(){return this.value}};
            var rewriteUrl=function(url){var query=new Query(url);var n=query.get("n");if(n)query.set("n",n.split("").reverse().join(""));return query.toString()};
            function consume(entry){entry.url&&(entry.url=rewriteUrl(entry.url))}
        """.trimIndent()
        val executable = YoutubePlayerTransformLocator.locateExecutable(source)
        val function = executable.functions.urlThrottling
        val program = executable.urlThrottlingProgram
        assertEquals("rewriteUrl", function)
        assertNotNull(program)
        val input = "https://r1.googlevideo.com/videoplayback?itag=18&n=abcdef"

        val output = runtime.executeFunction(program.orEmpty(), function.orEmpty(), input)

        assertEquals("https://r1.googlevideo.com/videoplayback?itag=18&n=fedcba", output)
        assertEquals(output, YoutubePlayerUrlTransformer.acceptUrlNTransform(input, output))
    }

    @Test
    fun executesMultipleTransformsFromOneFullPlayerBundle() = runBlocking {
        val source = """
            var _yt_player={};(function(g){var window=this;
            function rewrite(url){return url.replace(/([?&]n=)([^&]*)/,function(_,prefix,value){return prefix+value.split('').reverse().join('')})}
            })(_yt_player);
        """.trimIndent()
        val inputs = listOf("abcdef", "123456789", "A5g1S3m9Q2v8")
        val calls = inputs.map { value ->
            YoutubePlayerJavaScriptRuntime.FunctionCall(
                functionName = "rewrite",
                input = "https://r1.googlevideo.com/videoplayback?itag=18&n=$value",
            )
        }

        val outputs = runtime.executeFullPlayerFunctions(source, calls)

        assertEquals(
            listOf(
                "https://r1.googlevideo.com/videoplayback?itag=18&n=fedcba",
                "https://r1.googlevideo.com/videoplayback?itag=18&n=987654321",
                "https://r1.googlevideo.com/videoplayback?itag=18&n=8v2Q9m3S1g5A",
            ),
            outputs,
        )
    }

    @Test
    fun executesMarkerSelectedUrlObjectNTransformInFullBundle() = runBlocking {
        val source = """
            function PlayerUrl(value) { this.url = value; }
            PlayerUrl.prototype.get = function(name) {
              var query = this.url.split('?')[1] || '';
              var parts = query.split('&');
              for (var i = 0; i < parts.length; i++) {
                var pair = parts[i].split('=');
                if (decodeURIComponent(pair[0]) === name) return decodeURIComponent(pair.slice(1).join('='));
              }
              return null;
            };
            PlayerUrl.prototype.gf = function() {
              var match = /([?&]n=)([^&]*)/.exec(this.url);
              if (!match) return;
              var value = decodeURIComponent(match[2]).split('').reverse().join('');
              this.url = this.url.replace(match[0], match[1] + encodeURIComponent(value));
            };
            function markerCandidate(value, key, signature) { return new PlayerUrl(value); }
        """.trimIndent()
        val input = "https://rr1.googlevideo.com/videoplayback?v=fixture&n=abcd&range=0-1"

        val result = runtime.executeFullPlayerUrlObjectNTransforms(source, listOf("markerCandidate"), listOf(input)).single()

        assertTrue(result.candidateFound)
        assertTrue(result.methodCount > 0)
        assertTrue(result.nChanged)
        assertNotNull(result.url?.let { YoutubePlayerUrlTransformer.acceptUrlNTransform(input, it) })
    }

    @Test
    fun interruptsNonTerminatingTransformWithinRuntimeLimit() = runBlocking {
        val startedAt = System.nanoTime()
        try {
            runtime.executeFunction("function spin(value) { while (true) {} }", "spin", "bounded")
            fail("A non-terminating transform must be interrupted.")
        } catch (expected: IOException) {
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals("YouTube player transform timed out.", expected.message)
            if (elapsedMillis > 20_000L) fail("Transform interruption took ${elapsedMillis}ms.")
        }
    }
}
