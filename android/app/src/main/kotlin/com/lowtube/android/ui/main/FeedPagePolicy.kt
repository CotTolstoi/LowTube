package com.lowtube.android.ui.main

/** Client-side page sizing for feeds whose server controls each continuation response size. */
internal object FeedPagePolicy {
    const val DEFAULT_PAGE_SIZE = 25
    const val MIN_PAGE_SIZE = 1
    const val MAX_PAGE_SIZE = 250

    fun nextDisplayLimit(currentLimit: Int, pageSize: Int, availableCount: Int): Int {
        require(pageSize in MIN_PAGE_SIZE..MAX_PAGE_SIZE)
        require(currentLimit >= 0 && availableCount >= 0)
        return minOf(currentLimit + pageSize, availableCount)
    }

    fun hasBufferedResults(availableCount: Int, displayLimit: Int): Boolean = availableCount > displayLimit
}
