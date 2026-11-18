package com.lowtube.android.data

/** Search history is only for text queries; direct links and URI values are not stored. */
internal object SearchHistoryPolicy {
    private val uriScheme = Regex("(?i)^[a-z][a-z0-9+.-]*://")
    private val youtubeLinkPrefix = Regex("(?i)^(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be)(?:/|$)")

    fun isLink(value: String): Boolean = uriScheme.containsMatchIn(value) || youtubeLinkPrefix.containsMatchIn(value)
}
