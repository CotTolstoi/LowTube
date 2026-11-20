package com.lowtube.android.ui.playback

import com.lowtube.android.model.SearchPage
import com.lowtube.android.model.VideoItem

internal data class ShortsContinuationResult(
    val items: List<VideoItem>,
    val continuation: String?,
    val pagesRead: Int,
)

/** Reads a bounded number of continuation pages until new Shorts are available. */
internal suspend fun loadShortsContinuation(
    currentItems: List<VideoItem>,
    initialContinuation: String?,
    maxPages: Int = MAX_SHORTS_EMPTY_PAGES_PER_GESTURE,
    maxItems: Int = MAX_SHORTS_SESSION_ITEMS,
    fetchPage: suspend (String) -> SearchPage,
): ShortsContinuationResult {
    require(maxPages in 1..MAX_SHORTS_EMPTY_PAGES_PER_GESTURE)
    require(maxItems in 1..MAX_SHORTS_SESSION_ITEMS)
    if (initialContinuation == null || currentItems.size >= maxItems) {
        return ShortsContinuationResult(emptyList(), initialContinuation, 0)
    }

    val knownIds = currentItems.mapTo(HashSet()) { it.id }
    var continuation = initialContinuation
    var newItems = emptyList<VideoItem>()
    var pagesRead = 0
    while (continuation != null && newItems.isEmpty() && pagesRead < maxPages) {
        val requestedContinuation = continuation
        val page = fetchPage(requestedContinuation)
        continuation = page.continuation
        newItems = page.items.asSequence()
            .filter { it.kind == "shorts" && knownIds.add(it.id) }
            .take(maxItems - currentItems.size)
            .toList()
        pagesRead++
        if (continuation == requestedContinuation) break
    }
    return ShortsContinuationResult(newItems, continuation, pagesRead)
}

internal const val MAX_SHORTS_EMPTY_PAGES_PER_GESTURE = 3
internal const val MAX_SHORTS_SESSION_ITEMS = 5_000
