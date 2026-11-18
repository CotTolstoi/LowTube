package com.lowtube.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryPolicyTest {
    @Test
    fun textQueriesCanBeStored() {
        assertTrue(SearchHistoryPolicy.isLink("space exploration news" ).not())
        assertTrue(SearchHistoryPolicy.isLink("best recipes for dinner").not())
    }

    @Test
    fun directUrlsAreNotStored() {
        assertTrue(SearchHistoryPolicy.isLink("https://www.youtube.com/watch?v=6kp91gVA9To"))
        assertTrue(SearchHistoryPolicy.isLink("m.youtube.com/shorts/6kp91gVA9To"))
        assertTrue(SearchHistoryPolicy.isLink("https://example.org/search?q=private"))
    }

    @Test
    fun youtubeHostMatchDoesNotMatchLookalikeDomain() {
        assertFalse(SearchHistoryPolicy.isLink("youtube.com.example.org/watch?v=123"))
        assertFalse(SearchHistoryPolicy.isLink("news about youtube.com"))
    }
}
