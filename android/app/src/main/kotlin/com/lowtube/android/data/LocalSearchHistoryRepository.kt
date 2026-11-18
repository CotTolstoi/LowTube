package com.lowtube.android.data

import android.content.Context

/** Small, device-only list of recent text searches. Links are deliberately never persisted. */
class LocalSearchHistoryRepository(context: Context, preferencesName: String = PREFERENCES_NAME) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun recent(): List<String> = preferences.getString(KEY_RECENT, "")
        .orEmpty()
        .split(ENTRY_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .take(MAX_ITEMS)

    fun record(query: String): List<String> {
        val clean = query.trim().take(MAX_QUERY_LENGTH)
        if (clean.isBlank() || SearchHistoryPolicy.isLink(clean)) return recent()
        val updated = (listOf(clean) + recent().filterNot { it.equals(clean, ignoreCase = true) }).take(MAX_ITEMS)
        preferences.edit().putString(KEY_RECENT, updated.joinToString(ENTRY_SEPARATOR)).apply()
        return updated
    }

    fun clear() {
        preferences.edit().remove(KEY_RECENT).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "lowtube_search_history"
        const val KEY_RECENT = "recent_text_queries"
        const val ENTRY_SEPARATOR = "\n"
        const val MAX_ITEMS = 12
        const val MAX_QUERY_LENGTH = 200
    }
}
