package com.lowtube.android.data

import com.lowtube.android.model.SavedChannel
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SubscriptionFeedRefresh(
    val videos: List<VideoItem>,
    val refreshedChannels: Int,
    val failedChannels: List<String>,
    val remainingChannels: Int,
    val failureDetails: List<String> = emptyList(),
    val browseFallbackChannels: Int = 0,
)

/** Explicit, bounded refresh of local channel uploads using YouTube's public Atom feeds. */
class LocalSubscriptionFeedRepository(
    private val library: LocalLibraryRepository,
    private val innerTube: InnerTubeSearchRepository,
) {
    suspend fun refreshBatch(language: String = "en", preset: Int = 1, accountEmail: String = ""): SubscriptionFeedRefresh =
        refreshMutex.withLock { refreshBatchOnIo(language, preset, accountEmail) }

    private suspend fun refreshBatchOnIo(language: String, preset: Int, accountEmail: String): SubscriptionFeedRefresh = withContext(Dispatchers.IO) {
        val channels = library.channels(accountEmail)
        if (channels.isEmpty()) return@withContext SubscriptionFeedRefresh(emptyList(), 0, emptyList(), 0)

        val policy = SubscriptionFeedRefreshPolicy.fromPreset(preset)
        val selected = library.channelsForFeedRefresh(channels, policy.channelsPerBatch)
        val gate = Semaphore(policy.maxConcurrentRequests)
        val outcomes = coroutineScope {
            selected.map { channel ->
                async {
                    gate.withPermit {
                        try {
                            val (items, usedBrowseFallback) = fetch(channel, language)
                            if (library.replaceChannelFeed(channel.id, items, System.currentTimeMillis())) {
                                ChannelResult(channel, null, usedBrowseFallback)
                            } else null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            runCatching { library.markChannelFeedAttempt(channel.id, System.currentTimeMillis()) }
                            ChannelResult(channel, failure.message ?: "Feed unavailable", false)
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val failed = outcomes.filter { it.error != null }.map { it.channel.title.ifBlank { it.channel.id } }
        SubscriptionFeedRefresh(
            videos = library.subscriptionFeed(accountEmail),
            refreshedChannels = outcomes.count { it.error == null },
            failedChannels = failed,
            remainingChannels = library.unattemptedFeedChannelCount(accountEmail),
            failureDetails = outcomes.mapNotNull { it.error?.take(160) },
            browseFallbackChannels = outcomes.count { it.usedBrowseFallback },
        )
    }

    fun cached(accountEmail: String = ""): List<VideoItem> = library.subscriptionFeed(accountEmail)

    private suspend fun fetch(channel: SavedChannel, language: String): Pair<List<VideoItem>, Boolean> = try {
        fetchAtom(channel, language) to false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (atomFailure: Exception) {
        val fallbackItems = innerTube.search(channel.url, language = language).items
            .asSequence()
            .filter { it.channelId == channel.id && it.kind != "playlist" && VIDEO_ID.matches(it.id) }
            .distinctBy { it.id }
            .take(MAX_ITEMS_PER_CHANNEL)
            .toList()
        if (fallbackItems.isEmpty()) throw IOException(atomFailure.message ?: "Channel feed unavailable.", atomFailure)
        // A refresh timestamp would push undated uploads ahead of genuinely recent videos.
        fallbackItems to true
    }

    private fun fetchAtom(channel: SavedChannel, language: String): List<VideoItem> {
        require(CHANNEL_ID.matches(channel.id)) { "Invalid local channel ID." }
        val connection = (URL("https://www.youtube.com/feeds/videos.xml?channel_id=${channel.id}").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Channel feed request failed with HTTP $status.")
            if (connection.contentLength > MAX_RESPONSE_BYTES) throw IOException("Channel feed exceeds the 2 MB limit.")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Channel feed exceeds the 2 MB limit.")
                    bytes.write(buffer, 0, count)
                }
            }
            return parseFeed(bytes.toByteArray(), channel, language)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFeed(bytes: ByteArray, channel: SavedChannel, language: String): List<VideoItem> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
        var rootName = ""
        var rootChannelId = ""
        var inEntry = false
        var inAuthor = false
        var currentId = ""
        var title = ""
        var published = ""
        var author = ""
        val result = mutableListOf<VideoItem>()
        var depth = 0
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (depth > MAX_XML_DEPTH) throw IOException("Channel feed XML is too deeply nested.")
                    val name = parser.name
                    val namespace = parser.namespace.orEmpty()
                    if (depth == 1 && namespace == ATOM_NAMESPACE) rootName = name
                    if (depth == 2 && name == "entry" && namespace == ATOM_NAMESPACE) {
                        inEntry = true
                        currentId = ""
                        title = ""
                        published = ""
                        author = ""
                    } else if (name == "author" && inEntry && namespace == ATOM_NAMESPACE) {
                        inAuthor = true
                    } else if (depth == 2 && name == "channelId" && namespace == YOUTUBE_NAMESPACE) {
                        rootChannelId = parser.nextText().trim()
                        depth--
                    } else if (inEntry) {
                        when {
                            name == "videoId" && namespace == YOUTUBE_NAMESPACE -> { currentId = parser.nextText().trim(); depth-- }
                            name == "title" && namespace == ATOM_NAMESPACE -> { title = parser.nextText().trim(); depth-- }
                            name == "published" && namespace == ATOM_NAMESPACE -> { published = parser.nextText().trim(); depth-- }
                            name == "name" && namespace == ATOM_NAMESPACE && inAuthor -> { author = parser.nextText().trim(); depth-- }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "author" && parser.namespace == ATOM_NAMESPACE) inAuthor = false
                    if (depth == 2 && parser.name == "entry" && parser.namespace == ATOM_NAMESPACE) {
                        inEntry = false
                        if (VIDEO_ID.matches(currentId) && title.isNotBlank()) {
                            val publishedAt = parseDate(published)
                            result += VideoItem(
                                id = currentId,
                                title = title.take(1000),
                                channel = author.ifBlank { channel.title }.take(250),
                                channelId = channel.id,
                                published = formatDate(publishedAt, language),
                                publishedAtMs = publishedAt,
                                duration = "—",
                                thumbnailUrl = "https://i.ytimg.com/vi/$currentId/hqdefault.jpg",
                                url = "https://www.youtube.com/watch?v=$currentId",
                            )
                        }
                        if (result.size >= MAX_ITEMS_PER_CHANNEL) return validate(rootName, rootChannelId, channel, result)
                    }
                    depth--
                }
                XmlPullParser.END_DOCUMENT -> return validate(rootName, rootChannelId, channel, result)
            }
        }
    }

    private fun validate(rootName: String, feedChannelId: String, channel: SavedChannel, videos: List<VideoItem>): List<VideoItem> {
        if (rootName != "feed" || feedChannelId != channel.id) throw IOException("YouTube returned a feed for a different channel.")
        return videos.distinctBy { it.id }
    }

    private fun parseDate(value: String): Long {
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
        } ?: 0L
    }

    private fun formatDate(time: Long, language: String): String = runCatching {
        val locale = Locale.forLanguageTag(language).takeUnless { it.language.isBlank() } ?: Locale.ENGLISH
        SimpleDateFormat("yyyy-MM-dd", locale).format(Date(time))
    }.getOrDefault("")

    private data class ChannelResult(val channel: SavedChannel, val error: String?, val usedBrowseFallback: Boolean)

    companion object {
        // The worker and UI use separate repository instances but share the feed database.
        private val refreshMutex = Mutex()
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_ITEMS_PER_CHANNEL = 30
        private const val MAX_XML_DEPTH = 32
        private const val REQUEST_TIMEOUT_MS = 15_000
        private const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"
        private const val YOUTUBE_NAMESPACE = "http://www.youtube.com/xml/schemas/2015"
        private const val USER_AGENT = "LowTube/Android"
        private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
