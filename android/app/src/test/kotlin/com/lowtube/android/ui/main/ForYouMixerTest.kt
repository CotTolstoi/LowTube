package com.lowtube.android.ui.main

import com.lowtube.android.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ForYouMixerTest {
    @Test
    fun interleavesNewestFollowedUploadsAndExploreResults() {
        val explore = listOf(video("explore-1"), video("explore-2"))
        val followed = listOf(video("follow-old", publishedAtMs = 10), video("follow-new", publishedAtMs = 20))

        val result = ForYouMixer.blend(explore, followed)

        assertEquals(listOf("follow-new", "explore-1", "follow-old", "explore-2"), result.map { it.id })
    }

    @Test
    fun duplicateVideoIdsAppearOnlyOnce() {
        val result = ForYouMixer.blend(
            exploreItems = listOf(video("shared"), video("explore")),
            followedChannelItems = listOf(video("shared", publishedAtMs = 10), video("followed")),
        )

        assertEquals(listOf("shared", "explore", "followed"), result.map { it.id })
    }

    @Test
    fun resultRespectsTheRequestedLimit() {
        val result = ForYouMixer.blend(
            exploreItems = listOf(video("explore-1"), video("explore-2")),
            followedChannelItems = listOf(video("followed-1"), video("followed-2")),
            limit = 3,
        )

        assertEquals(listOf("followed-1", "explore-1", "followed-2"), result.map { it.id })
    }

    private fun video(id: String, publishedAtMs: Long = 0L) =
        VideoItem(id = id, title = id, url = "https://www.youtube.com/watch?v=$id", publishedAtMs = publishedAtMs)
}
