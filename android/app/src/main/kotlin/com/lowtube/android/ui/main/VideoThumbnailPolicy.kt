package com.lowtube.android.ui.main

import com.lowtube.android.model.VideoItem

/** Supplies the standard YouTube thumbnail when a video renderer omits its thumbnail object. */
internal object VideoThumbnailPolicy {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val videoKinds = setOf("video", "shorts", "live", "was_live")

    fun resolve(item: VideoItem): String {
        if (item.thumbnailUrl.isNotBlank()) return item.thumbnailUrl
        if (item.kind !in videoKinds || !videoIdPattern.matches(item.id)) return ""
        return "https://i.ytimg.com/vi/${item.id}/hqdefault.jpg"
    }
}
