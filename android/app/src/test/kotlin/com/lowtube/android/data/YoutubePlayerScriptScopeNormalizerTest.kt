package com.lowtube.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubePlayerScriptScopeNormalizerTest {
    @Test
    fun removesKnownPlayerWrapperAndProvidesGlobalAliases() {
        val source = "var _yt_player={};(function(g){var window=this;var decode=function(a){return helper(a)};})(_yt_player);"
        assertEquals(
            "var g={};var window=this;var decode=function(a){return helper(a)};",
            YoutubePlayerScriptScopeNormalizer.prepare(source),
        )
    }

    @Test
    fun leavesUnrecognizedBundleShapeUntouched() {
        val source = "(function(g){var decode=function(a){return a};})(player);"
        assertEquals(source, YoutubePlayerScriptScopeNormalizer.prepare(source))
    }
}
