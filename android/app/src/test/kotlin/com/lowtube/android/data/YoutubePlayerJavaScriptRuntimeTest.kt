package com.lowtube.android.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubePlayerJavaScriptRuntimeTest {
    @Test
    fun extractsMissingIdentifierFromWrappedQuickJsFailure() {
        val failure = IOException(
            "Player transform failed.",
            IllegalStateException("ReferenceError: 'Wca' is not defined"),
        )

        assertEquals("Wca", YoutubePlayerJavaScriptRuntime.unresolvedIdentifier(failure))
    }

    @Test
    fun ignoresNonReferenceErrors() {
        assertNull(YoutubePlayerJavaScriptRuntime.unresolvedIdentifier(IllegalStateException("TypeError: bad call")))
    }
}
