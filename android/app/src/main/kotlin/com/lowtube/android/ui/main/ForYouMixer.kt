package com.lowtube.android.ui.main

import com.lowtube.android.model.VideoItem

/** Mixes cached uploads from followed channels into guest recommendations without changing their order. */
internal object ForYouMixer {
    fun blend(
        exploreItems: List<VideoItem>,
        followedChannelItems: List<VideoItem>,
        limit: Int = MAX_ITEMS,
    ): List<VideoItem> {
        if (limit <= 0) return emptyList()
        val explore = exploreItems.distinctBy { it.id }
        val followed = followedChannelItems
            .distinctBy { it.id }
            .sortedWith(compareByDescending<VideoItem> { it.publishedAtMs }.thenBy { it.id })
        val result = ArrayList<VideoItem>(minOf(limit, explore.size + followed.size))
        val seen = HashSet<String>()
        var exploreIndex = 0
        var followedIndex = 0

        fun appendNext(items: List<VideoItem>, startIndex: Int): Int {
            var index = startIndex
            while (index < items.size && result.size < limit) {
                val video = items[index++]
                if (seen.add(video.id)) {
                    result.add(video)
                    break
                }
            }
            return index
        }

        while (result.size < limit && (exploreIndex < explore.size || followedIndex < followed.size)) {
            if (followedIndex < followed.size) followedIndex = appendNext(followed, followedIndex)
            if (result.size >= limit) break
            if (exploreIndex < explore.size) exploreIndex = appendNext(explore, exploreIndex)
        }
        return result
    }

    private const val MAX_ITEMS = 5_000
}
