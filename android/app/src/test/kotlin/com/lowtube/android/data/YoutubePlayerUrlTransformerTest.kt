package com.lowtube.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubePlayerUrlTransformerTest {
    @Test
    fun replacesOnlyNQueryParameterAndPreservesOtherUrlParts() {
        val url = "https://r1.googlevideo.com/videoplayback?expire=123&n=old%2Bsig&itag=18#fragment"

        assertEquals(
            "https://r1.googlevideo.com/videoplayback?expire=123&n=new%20sig&itag=18#fragment",
            YoutubePlayerUrlTransformer.applyN(url, "new sig"),
        )
    }

    @Test
    fun collapsesDuplicateNParameters() {
        assertEquals(
            "https://r1.googlevideo.com/videoplayback?n=fixed&itag=18",
            YoutubePlayerUrlTransformer.applyN("https://r1.googlevideo.com/videoplayback?n=one&n=two&itag=18", "fixed"),
        )
    }

    @Test
    fun acceptsWholeUrlTransformOnlyWhenNChangesAndOriginAndPathStayFixed() {
        val original = "https://r1.googlevideo.com/videoplayback?expire=123&n=old&itag=18"
        val transformed = "https://r1.googlevideo.com/videoplayback?expire=123&n=new&itag=18&alr=yes"

        assertEquals(transformed, YoutubePlayerUrlTransformer.acceptUrlNTransform(original, transformed))
        assertNull(
            YoutubePlayerUrlTransformer.acceptUrlNTransform(
                original,
                "https://attacker.example/videoplayback?expire=123&n=new&itag=18",
            ),
        )
        assertNull(
            YoutubePlayerUrlTransformer.acceptUrlNTransform(
                original,
                "https://r1.googlevideo.com/other-path?expire=123&n=new&itag=18",
            ),
        )
        assertNull(YoutubePlayerUrlTransformer.acceptUrlNTransform(original, original))
    }

    @Test
    fun acceptsOnlySinglePathSegmentRewriteAfterNMarkerWithUnchangedQuery() {
        val original = "https://r1.googlevideo.com/videoplayback/n/old-token/chunk?expire=123&n=encoded&itag=18"
        val transformed = "https://r1.googlevideo.com/videoplayback/n/new-token/chunk?expire=123&n=encoded&itag=18"

        assertEquals(transformed, YoutubePlayerUrlTransformer.acceptUrlNTransform(original, transformed))
        assertNull(
            YoutubePlayerUrlTransformer.acceptUrlNTransform(
                original,
                "https://r1.googlevideo.com/videoplayback/n/new-token/other?expire=123&n=encoded&itag=18",
            ),
        )
        assertNull(
            YoutubePlayerUrlTransformer.acceptUrlNTransform(
                original,
                "https://r1.googlevideo.com/videoplayback/n/new-token/chunk?expire=124&n=encoded&itag=18",
            ),
        )
        assertNull(
            YoutubePlayerUrlTransformer.acceptUrlNTransform(
                original,
                "https://r1.googlevideo.com/videoplayback/n/new-token/chunk?expire=123&n=changed&itag=18",
            ),
        )
    }

    @Test
    fun appliesCipherSignatureUsingRequestedParameterName() {
        val cipher = "url=https%3A%2F%2Fr1.googlevideo.com%2Fvideoplayback%3Fitag%3D18&sp=sig& s=unused".replace("& ", "&")

        assertEquals(
            "https://r1.googlevideo.com/videoplayback?itag=18&sig=plain%2Fsignature",
            YoutubePlayerUrlTransformer.applySignatureCipher(cipher, "plain/signature"),
        )
    }

    @Test
    fun rejectsMalformedOrMissingChallenges() {
        assertNull(YoutubePlayerUrlTransformer.applyN("https://r1.googlevideo.com/video?itag=18", "fixed"))
        assertNull(YoutubePlayerUrlTransformer.applyN("https://r1.googlevideo.com/video?n=old", ""))
        assertNull(YoutubePlayerUrlTransformer.applySignatureCipher("s=obfuscated", "fixed"))
    }
}
