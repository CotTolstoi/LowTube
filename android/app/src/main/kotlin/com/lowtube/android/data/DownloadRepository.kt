package com.lowtube.android.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.lowtube.android.downloads.DownloadService
import com.lowtube.android.model.DownloadFormatOption
import com.lowtube.android.model.DownloadJob
import com.lowtube.android.model.DownloadRecord
import com.lowtube.android.model.DownloadStatus
import com.lowtube.android.model.DownloadedVideo
import com.lowtube.android.model.LocalVideoSource
import com.lowtube.android.model.VideoItem
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** LowTube-managed foreground transfer queue; pre-existing system downloads remain visible. */
class DownloadRepository private constructor(
    context: Context,
    private val jobs: DownloadJobStore,
    preferencesName: String,
) : AutoCloseable {
    constructor(context: Context) : this(
        context.applicationContext,
        DownloadJobStore(context.applicationContext),
        DEFAULT_PREFERENCES_NAME,
    )

    internal constructor(context: Context, databaseName: String) :
        this(
            context.applicationContext,
            DownloadJobStore(context.applicationContext, databaseName),
            "${DEFAULT_PREFERENCES_NAME}_$databaseName",
        )

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val resolver = InnerTubeSearchRepository(appContext)

    suspend fun options(video: VideoItem, streamPreferences: DownloadStreamPreferences, audioOnly: Boolean): List<DownloadFormatOption> =
        resolver.resolveDownloadOptions(video, streamPreferences, audioOnly)

    fun enqueue(video: VideoItem, option: DownloadFormatOption): Long {
        require(option.stream.video.id == video.id) { "Selected format belongs to another video." }
        val uri = Uri.parse(option.stream.uri)
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))) { "Selected download format has an untrusted stream address." }
        require(option.stream.mimeType in SUPPORTED_MIME_TYPES) { "Selected download format is not supported." }
        require(if (option.audioOnly) option.stream.isAudioOnly else option.stream.isMuxed || option.requiresMuxing) { "Selected format is not a downloadable video." }
        if (option.requiresMuxing) {
            val audio = option.audioStream ?: throw IllegalArgumentException("The selected MP4 format has no audio track.")
            val audioUri = Uri.parse(audio.uri)
            val audioHost = audioUri.host?.lowercase().orEmpty()
            require(audioUri.scheme == "https" && (audioHost == "googlevideo.com" || audioHost.endsWith(".googlevideo.com"))) { "Selected audio format has an untrusted stream address." }
            require(option.stream.mimeType == "video/mp4" && audio.mimeType == "audio/mp4" && !option.stream.isMuxed && audio.isAudioOnly) { "Selected track pair cannot be combined into MP4." }
        }
        val fileName = buildFileName(video, option)
        val partialDir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir, "LowTube/.partial")
        if (!partialDir.isDirectory && !partialDir.mkdirs() && !partialDir.isDirectory) throw IOException("Android could not prepare private download storage.")
        val partial = File(partialDir, "$fileName.part")
        val id = jobs.insert(encodeVideo(video), option.key, option.audioOnly, fileName, option.stream.mimeType, option.stream.userAgent, partial.absolutePath)
        startService()
        return id
    }

    suspend fun retry(id: Long, streamPreferences: DownloadStreamPreferences = DownloadStreamPreferences()) {
        if (id >= OWN_ID_BASE) {
            val job = jobs.get(id) ?: throw IllegalStateException("This LowTube download no longer exists.")
            if (job.status == DownloadStatus.RUNNING || job.status == DownloadStatus.PENDING) return
            jobs.update(id, DownloadStatus.PENDING)
            startService()
            return
        }
        val video = storedLegacyVideo(id) ?: throw IllegalStateException("The original video details for this download are unavailable.")
        val audioOnly = preferences.getBoolean(audioOnlyKey(id), false)
        val selectedKey = preferences.getString(formatKeyKey(id), "").orEmpty()
        val option = if (selectedKey.isBlank()) resolver.resolveDownloadOptions(video, streamPreferences, audioOnly).firstOrNull()
            ?: throw IOException("YouTube did not return a directly downloadable format that matches these settings.")
        else resolver.resolveDownloadOption(video, streamPreferences, audioOnly, selectedKey)
        enqueue(video, option)
        manager.remove(id)
        val remaining = trackedIds() - id
        preferences.edit().putStringSet(KEY_IDS, remaining.map(Long::toString).toSet()).remove(videoKey(id)).remove(mimeKey(id)).remove(audioOnlyKey(id)).remove(formatKeyKey(id)).apply()
    }

    fun cancel(id: Long) {
        if (id >= OWN_ID_BASE) {
            val intent = Intent(appContext, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL).putExtra(DownloadService.EXTRA_ID, id)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(appContext, intent) else appContext.startService(intent)
        } else manager.remove(id)
    }

    fun records(): List<DownloadRecord> {
        val custom = jobs.all().map { job ->
            DownloadRecord(job.id, videoTitle(job.videoJson), job.status, job.reason, job.downloadedBytes, job.totalBytes, job.localUri, job.mimeType)
        }
        return (custom + legacyRecords()).sortedByDescending { it.id }
    }

    /** Aggregates statuses only; it never reads or returns titles, video identifiers, URLs, or paths. */
    internal fun diagnosticStatusCounts(): DownloadJobCounts {
        val customCounts = DownloadJobCounts.fromCounts(jobs.statusCounts())
        val legacyIds = trackedIds()
        val boundedIds = legacyIds.sortedDescending().take(MAX_DIAGNOSTIC_LEGACY_IDS).toLongArray()
        if (boundedIds.isEmpty()) return customCounts
        val legacyCounts = mutableMapOf<Int, Long>()
        manager.query(DownloadManager.Query().setFilterById(*boundedIds))?.use { cursor ->
            val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusColumn >= 0) {
                while (cursor.moveToNext()) {
                    val status = cursor.getInt(statusColumn)
                    legacyCounts[status] = (legacyCounts[status] ?: 0L) + 1L
                }
            }
        }
        return customCounts + DownloadJobCounts.fromCounts(
            legacyCounts,
            legacyScanTruncated = legacyIds.size > MAX_DIAGNOSTIC_LEGACY_IDS,
        )
    }

    fun resumePending() {
        if (jobs.pending() != null) startService()
    }

    override fun close() = jobs.close()

    fun remove(id: Long) {
        if (id >= OWN_ID_BASE) {
            jobs.get(id)?.let { job ->
                File(job.partialPath).delete()
                File(job.partialPath + ".video").delete()
                File(job.partialPath + ".audio").delete()
                File(job.partialPath + ".video.total").delete()
                File(job.partialPath + ".audio.total").delete()
                File(job.partialPath + ".video.total.tmp").delete()
                File(job.partialPath + ".audio.total.tmp").delete()
                if (job.localUri.isNotBlank()) runCatching {
                    val uri = Uri.parse(job.localUri)
                    if (uri.scheme == "file") File(uri.path.orEmpty()).delete()
                    else appContext.contentResolver.delete(uri, null, null)
                }
            }
            jobs.remove(id)
        } else {
            manager.remove(id)
            val remaining = trackedIds() - id
            preferences.edit().putStringSet(KEY_IDS, remaining.map(Long::toString).toSet()).remove(videoKey(id)).remove(mimeKey(id)).remove(audioOnlyKey(id)).remove(formatKeyKey(id)).apply()
        }
    }

    fun open(id: Long) {
        if (id >= OWN_ID_BASE) {
            val job = jobs.get(id) ?: throw IllegalStateException("This download no longer exists.")
            if (job.localUri.isBlank()) throw IllegalStateException("This file is not ready yet.")
            val storedUri = Uri.parse(job.localUri)
            val uri = if (storedUri.scheme == "file") FileProvider.getUriForFile(appContext, appContext.packageName + ".fileprovider", File(storedUri.path.orEmpty())) else storedUri
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, job.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            return
        }
        val uri = manager.getUriForDownloadedFile(id) ?: throw IllegalStateException("This file is not ready yet.")
        val mime = preferences.getString(mimeKey(id), "application/octet-stream") ?: "application/octet-stream"
        appContext.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun playableVideo(id: Long): DownloadedVideo {
        if (id >= OWN_ID_BASE) {
            val job = jobs.get(id) ?: throw IllegalStateException("This LowTube download no longer exists.")
            if (job.status != DownloadStatus.SUCCESSFUL || job.audioOnly || job.localUri.isBlank()) {
                throw IllegalStateException("Only completed video downloads can play in LowTube.")
            }
            val video = decodeVideo(job.videoJson)
                ?: throw IllegalStateException("The original video details for this download are unavailable.")
            return DownloadedVideo(video, localVideoSource(job.localUri, job.mimeType))
        }

        if (preferences.getBoolean(audioOnlyKey(id), false)) {
            throw IllegalStateException("Only completed video downloads can play in LowTube.")
        }
        val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            ?: throw IllegalStateException("This download could not be opened.")
        val successful = cursor.use {
            it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
        if (!successful) throw IllegalStateException("Only completed video downloads can play in LowTube.")
        val video = storedLegacyVideo(id)
            ?: throw IllegalStateException("The original video details for this download are unavailable.")
        val uri = manager.getUriForDownloadedFile(id)?.toString()
            ?: throw IllegalStateException("This downloaded file is no longer available.")
        val mimeType = preferences.getString(mimeKey(id), "application/octet-stream").orEmpty()
        return DownloadedVideo(video, localVideoSource(uri, mimeType))
    }

    private fun localVideoSource(rawUri: String, mimeType: String): LocalVideoSource {
        require(mimeType.startsWith("video/")) { "Only downloaded video files can play in LowTube." }
        val storedUri = Uri.parse(rawUri)
        val playbackUri = when (storedUri.scheme) {
            "content" -> storedUri
            "file" -> {
                val file = File(storedUri.path.orEmpty())
                require(file.isFile) { "This downloaded file is no longer available." }
                FileProvider.getUriForFile(appContext, appContext.packageName + ".fileprovider", file)
            }
            else -> throw IllegalStateException("This downloaded file has an unsupported location.")
        }
        return LocalVideoSource(playbackUri.toString(), mimeType)
    }

    private fun legacyRecords(): List<DownloadRecord> {
        val ids = trackedIds()
        if (ids.isEmpty()) return emptyList()
        val cursor = manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray())) ?: return emptyList()
        cursor.use {
            val result = mutableListOf<DownloadRecord>()
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                result += DownloadRecord(id, it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty(), it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)).toString(), it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0), it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0), it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty(), preferences.getString(mimeKey(id), "application/octet-stream").orEmpty())
            }
            val found = result.map { it.id }.toSet()
            val missing = ids - found
            if (missing.isNotEmpty()) {
                val edit = preferences.edit().putStringSet(KEY_IDS, (ids - missing).map(Long::toString).toSet())
                missing.forEach { edit.remove(videoKey(it)).remove(mimeKey(it)).remove(audioOnlyKey(it)).remove(formatKeyKey(it)) }
                edit.apply()
            }
            return result
        }
    }

    private fun startService() {
        val intent = Intent(appContext, DownloadService::class.java).setAction(DownloadService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(appContext, intent) else appContext.startService(intent)
    }

    private fun buildFileName(video: VideoItem, option: DownloadFormatOption): String {
        val extension = when (option.stream.mimeType) { "video/webm", "audio/webm" -> "webm"; "audio/mp4" -> "m4a"; "audio/mpeg" -> "mp3"; else -> "mp4" }
        val safe = video.title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().replace(Regex("\\s+"), " ").trimEnd('.', ' ').take(70).ifBlank { "LowTube video" }
        val uniqueTag = UUID.randomUUID().toString().take(8)
        val audioTag = option.audioStream?.itag?.let { "_a$it" }.orEmpty()
        return "LowTube_${safe}_${video.id}_${option.stream.itag}${audioTag}_$uniqueTag.$extension"
    }

    private fun encodeVideo(video: VideoItem) = JSONObject().put("id", video.id).put("title", video.title).put("channel", video.channel).put("channelId", video.channelId).put("published", video.published).put("duration", video.duration).put("kind", video.kind).put("liveStatus", video.liveStatus).put("thumbnailUrl", video.thumbnailUrl).put("url", video.url).toString()
    private fun videoTitle(json: String) = runCatching { JSONObject(json).optString("title", "LowTube download") }.getOrDefault("LowTube download")
    private fun storedLegacyVideo(id: Long): VideoItem? = runCatching {
        val root = JSONObject(preferences.getString(videoKey(id), null) ?: return null)
        VideoItem(root.getString("id"), root.getString("title"), root.optString("channel"), root.optString("channelId"), root.optString("published"), root.optString("duration", "—"), root.optString("kind", "video"), root.optString("liveStatus"), root.optString("thumbnailUrl"), root.getString("url"))
    }.getOrNull()
    private fun decodeVideo(json: String): VideoItem? = runCatching {
        val root = JSONObject(json)
        VideoItem(root.getString("id"), root.getString("title"), root.optString("channel"), root.optString("channelId"), root.optString("published"), root.optString("duration", "—"), root.optString("kind", "video"), root.optString("liveStatus"), root.optString("thumbnailUrl"), root.getString("url"))
    }.getOrNull()
    private fun videoKey(id: Long) = "download_video_$id"
    private fun mimeKey(id: Long) = "download_mime_$id"
    private fun audioOnlyKey(id: Long) = "download_audio_only_$id"
    private fun formatKeyKey(id: Long) = "download_format_key_$id"
    private fun trackedIds(): Set<Long> = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()

    companion object {
        private const val KEY_IDS = "download_manager_ids"
        private const val DEFAULT_PREFERENCES_NAME = "lowtube_downloads"
        private const val OWN_ID_BASE = 1_000_000_000_000L
        private const val MAX_DIAGNOSTIC_LEGACY_IDS = 500
        private val SUPPORTED_MIME_TYPES = setOf("video/mp4", "video/webm", "audio/mp4", "audio/webm", "audio/mpeg")
    }
}
