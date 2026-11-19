package com.lowtube.android.playback

import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackArtworkPolicyTest {
    @Test
    fun usesCanonicalYoutubeThumbnailWhenItemHasNoThumbnail() {
        assertEquals(
            "https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg",
            PlaybackArtworkPolicy.resolve(video(thumbnailUrl = "")),
        )
    }

    @Test
    fun acceptsOnlyHttpsThumbnailForTheSameVideoOnYoutubeImageHost() {
        assertEquals(
            "https://i.ytimg.com/vi/aqz-KE-bpKQ/mqdefault.jpg",
            PlaybackArtworkPolicy.resolve(video(thumbnailUrl = "https://i.ytimg.com/vi/aqz-KE-bpKQ/mqdefault.jpg")),
        )
        assertEquals(
            "https://i.ytimg.com/vi_webp/aqz-KE-bpKQ/maxresdefault.webp",
            PlaybackArtworkPolicy.resolve(video(thumbnailUrl = "https://i.ytimg.com/vi_webp/aqz-KE-bpKQ/maxresdefault.webp")),
        )
        assertEquals(
            "https://img.youtube.com/vi/aqz-KE-bpKQ/hqdefault.jpg",
            PlaybackArtworkPolicy.resolve(video(thumbnailUrl = "https://img.youtube.com/vi/aqz-KE-bpKQ/hqdefault.jpg")),
        )
        listOf(
            "http://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg",
            "https://images.example/vi/aqz-KE-bpKQ/hqdefault.jpg",
            "https://i.ytimg.com/vi/otherVideo01/hqdefault.jpg",
            "https://user@i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg",
            "https://i.ytimg.com:443/vi/aqz-KE-bpKQ/hqdefault.jpg",
            "https://i.ytimg.com.evil.example/vi/aqz-KE-bpKQ/hqdefault.jpg",
        ).forEach { candidate ->
            assertNull(candidate, PlaybackArtworkPolicy.resolve(video(thumbnailUrl = candidate)))
        }
    }

    @Test
    fun refusesArtworkForInvalidVideoIdsAndOversizedUrls() {
        assertNull(PlaybackArtworkPolicy.resolve(video(id = "invalid")))
        assertNull(
            PlaybackArtworkPolicy.resolve(
                video(thumbnailUrl = "https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg?${"x".repeat(2048)}"),
            ),
        )
    }

    private fun video(
        id: String = "aqz-KE-bpKQ",
        thumbnailUrl: String = "",
    ) = VideoItem(
        id = id,
        title = "Fixture",
        thumbnailUrl = thumbnailUrl,
        url = "https://www.youtube.com/watch?v=$id",
    )
}
