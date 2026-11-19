package com.lowtube.android.ui.main

import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoThumbnailPolicyTest {
    @Test
    fun keepsThumbnailProvidedByYoutube() {
        val item = fixture().copy(thumbnailUrl = "https://example.test/custom.jpg")

        assertEquals("https://example.test/custom.jpg", VideoThumbnailPolicy.resolve(item))
    }

    @Test
    fun buildsYoutubeThumbnailForShortsWithoutRendererThumbnail() {
        val item = fixture().copy(id = "dQw4w9WgXcQ", kind = "shorts")

        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            VideoThumbnailPolicy.resolve(item),
        )
    }

    @Test
    fun doesNotTreatPlaylistIdsAsVideoIds() {
        val item = fixture().copy(id = "PL1234567890", kind = "playlist")

        assertEquals("", VideoThumbnailPolicy.resolve(item))
    }

    @Test
    fun rejectsMalformedVideoIds() {
        val item = fixture().copy(id = "short", kind = "video")

        assertEquals("", VideoThumbnailPolicy.resolve(item))
    }

    private fun fixture() = VideoItem(
        id = "dQw4w9WgXcQ",
        title = "Thumbnail fixture",
        kind = "video",
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    )
}
