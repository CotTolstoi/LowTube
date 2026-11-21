package com.lowtube.android.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val requestLanguage: String = "en",
    val items: List<VideoItem> = emptyList(),
    val continuation: String? = null,
    val playlistTitle: String? = null,
    val channelHeader: com.lowtube.android.model.ChannelHeader? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val displayLimit: Int = FeedPagePolicy.DEFAULT_PAGE_SIZE,
    val searched: Boolean = false,
    val isHome: Boolean = false,
    val homeTopic: String = "science and technology",
    val discoveryLanguage: String = "any",
    val homeCacheKey: String = "",
    val error: String? = null,
    val homeRefreshFailed: Boolean = false,
)

class SearchViewModel(
    private val repository: InnerTubeSearchRepository = InnerTubeSearchRepository(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private val homePages = LinkedHashMap<String, SearchUiState>()
    private var searchJob: Job? = null
    private var generation = 0

    fun loadHome(
        language: String,
        topic: String = "science and technology",
        discoveryLanguage: String = "any",
        force: Boolean = false,
        requestFailedMessage: String = DEFAULT_REQUEST_FAILED_MESSAGE,
        pageSize: Int = FeedPagePolicy.DEFAULT_PAGE_SIZE,
    ) {
        require(pageSize in FeedPagePolicy.MIN_PAGE_SIZE..FeedPagePolicy.MAX_PAGE_SIZE)
        val cleanTopic = topic.trim().take(200).ifBlank { "science and technology" }
        val cleanDiscoveryLanguage = discoveryLanguage.takeIf { it in DISCOVERY_LANGUAGES } ?: "any"
        val cacheKey = "$language|$cleanTopic|$cleanDiscoveryLanguage"
        val current = mutableState.value
        if (!force && current.isHome && current.homeCacheKey == cacheKey && (current.loading || current.items.isNotEmpty())) return
        val cached = homePages[cacheKey]
        if (!force && cached != null) {
            generation++
            searchJob?.cancel()
            val restored = cached.copy(displayLimit = pageSize, loading = false, loadingMore = false, error = null)
            mutableState.value = restored
            rememberHomePage(restored)
            return
        }
        val requestGeneration = ++generation
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.value = SearchUiState(
                requestLanguage = language,
                items = cached?.items.orEmpty(),
                continuation = cached?.continuation,
                loading = true,
                displayLimit = pageSize,
                isHome = true,
                homeTopic = cleanTopic,
                discoveryLanguage = cleanDiscoveryLanguage,
                homeCacheKey = cacheKey,
            )
            try {
                val page = repository.recommendations(language, cleanTopic, cleanDiscoveryLanguage)
                if (requestGeneration == generation) {
                    val loaded = mutableState.value.copy(
                        items = page.items,
                        continuation = page.continuation,
                        loading = false,
                    )
                    mutableState.value = loaded
                    rememberHomePage(loaded)
                }
            } catch (error: Exception) {
                if (requestGeneration == generation) {
                    val failed = mutableState.value.copy(
                        loading = false,
                        error = safeMessage(error, requestFailedMessage),
                        homeRefreshFailed = true,
                    )
                    mutableState.value = failed
                }
            }
        }
    }

    fun showSearch() {
        generation++
        searchJob?.cancel()
        mutableState.value = SearchUiState()
    }

    fun setPageSize(pageSize: Int) {
        require(pageSize in FeedPagePolicy.MIN_PAGE_SIZE..FeedPagePolicy.MAX_PAGE_SIZE)
        val current = mutableState.value
        if (current.displayLimit == pageSize) return
        val updated = current.copy(displayLimit = pageSize)
        mutableState.value = updated
    }

    fun search(
        query: String,
        language: String,
        emptySearchMessage: String = DEFAULT_EMPTY_SEARCH_MESSAGE,
        requestFailedMessage: String = DEFAULT_REQUEST_FAILED_MESSAGE,
        pageSize: Int = FeedPagePolicy.DEFAULT_PAGE_SIZE,
    ) {
        require(pageSize in FeedPagePolicy.MIN_PAGE_SIZE..FeedPagePolicy.MAX_PAGE_SIZE)
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            generation++
            searchJob?.cancel()
            mutableState.value = SearchUiState(error = emptySearchMessage, searched = true, displayLimit = pageSize)
            return
        }
        val requestGeneration = ++generation
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.value = SearchUiState(query = normalized, loading = true, searched = true, displayLimit = pageSize)
            try {
                val page = repository.search(normalized, language = language)
                if (requestGeneration == generation) {
                    mutableState.value = mutableState.value.copy(
                        requestLanguage = language,
                        items = page.items,
                        continuation = page.continuation,
                        playlistTitle = page.playlistTitle,
                        channelHeader = page.channelHeader,
                        loading = false,
                    )
                }
            } catch (error: Exception) {
                if (requestGeneration == generation) {
                    mutableState.value = mutableState.value.copy(loading = false, error = safeMessage(error, requestFailedMessage))
                }
            }
        }
    }

    fun loadMore(
        requestFailedMessage: String = DEFAULT_REQUEST_FAILED_MESSAGE,
        pageSize: Int = FeedPagePolicy.DEFAULT_PAGE_SIZE,
        availableItemCount: Int = -1,
    ) {
        require(pageSize in FeedPagePolicy.MIN_PAGE_SIZE..FeedPagePolicy.MAX_PAGE_SIZE)
        val current = mutableState.value
        if (current.loading || current.loadingMore) return
        val availableCount = availableItemCount.takeIf { it >= 0 } ?: current.items.size
        if (FeedPagePolicy.hasBufferedResults(availableCount, current.displayLimit)) {
            mutableState.value = current.copy(
                displayLimit = FeedPagePolicy.nextDisplayLimit(current.displayLimit, pageSize, availableCount),
                error = null,
            )
            return
        }
        val token = current.continuation ?: return
        val requestGeneration = generation
        mutableState.value = current.copy(loadingMore = true, error = null, homeRefreshFailed = false)
        searchJob = viewModelScope.launch {
            try {
                val page = if (current.isHome) repository.recommendations(
                    current.requestLanguage,
                    current.homeTopic,
                    current.discoveryLanguage,
                    token,
                )
                    else repository.search(current.query, continuation = token, language = current.requestLanguage)
                if (requestGeneration == generation) {
                    val merged = (current.items + page.items).distinctBy { it.kind + ":" + it.id }.take(5_000)
                    val updated = mutableState.value.copy(
                        items = merged,
                        continuation = page.continuation?.takeUnless {
                            merged.size == 5_000 || (it == token && merged.size == current.items.size)
                        },
                        playlistTitle = page.playlistTitle ?: current.playlistTitle,
                        channelHeader = page.channelHeader ?: current.channelHeader,
                        loadingMore = false,
                        error = null,
                    )
                    mutableState.value = updated
                    if (updated.isHome) rememberHomePage(updated)
                }
            } catch (error: Exception) {
                if (requestGeneration == generation) {
                    mutableState.value = mutableState.value.copy(loadingMore = false, error = safeMessage(error, requestFailedMessage))
                }
            }
        }
    }

    private fun safeMessage(error: Exception, fallback: String): String =
        error.message?.take(240)?.ifBlank { fallback } ?: fallback

    private fun rememberHomePage(page: SearchUiState) {
        if (page.homeCacheKey.isBlank()) return
        homePages[page.homeCacheKey] = page
        while (homePages.size > MAX_HOME_PAGES) homePages.remove(homePages.keys.first())
    }

    companion object {
        private const val DEFAULT_EMPTY_SEARCH_MESSAGE = "Enter a video title or YouTube URL."
        private const val DEFAULT_REQUEST_FAILED_MESSAGE = "The request failed. Retry."
        private const val MAX_HOME_PAGES = 4
        private val DISCOVERY_LANGUAGES = setOf("any", "en", "uk", "ru", "pl", "de", "fr", "es", "it", "pt", "ja", "ko")
    }
}
