package com.lowtube.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubePlayerArrowCompatTest {
    @Test
    fun convertsExpressionBodyArrowWithParenthesizedParameters() {
        assertEquals(
            "var throttle=function(value){return value.slice(2);};",
            YoutubePlayerArrowCompat.normalize("var throttle = (value) => value.slice(2);", "throttle"),
        )
    }

    @Test
    fun convertsSingleParameterArrowAndNormalizesConstToVar() {
        assertEquals(
            "var sig=function(a){return a.split('').reverse().join('');};",
            YoutubePlayerArrowCompat.normalize("const sig = a => a.split('').reverse().join('');", "sig"),
        )
    }

    @Test
    fun leavesBlockBodiesAndUnrelatedDeclarationsUntouched() {
        val source = "const other = a => a; var sig = a => { return a; };"

        assertEquals(source, YoutubePlayerArrowCompat.normalize(source, "sig"))
    }
}
