package com.lowtube.android.downloads

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.lowtube.android.AppLocale
import com.lowtube.android.R
import com.lowtube.android.data.DownloadJobStore
import com.lowtube.android.data.DownloadStreamPreferences
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.model.DownloadJob
import com.lowtube.android.model.DownloadFormatOption
import com.lowtube.android.model.DownloadStatus
import com.lowtube.android.model.ResolvedVideoStream
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Owns LowTube's durable sequential queue and keeps active transfers visible as a foreground service. */
class DownloadService : Service() {
    private data class TransferOutcome(val downloaded: Long, val total: Long, val complete: Boolean)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var store: DownloadJobStore? = null
    private var testStreamUrl: String? = null
    private var testAudioStreamUrl: String? = null
    private var testPreparingDelayMillis = 0L
    private var testHoldPreparingNotification = false
    private var allowLoopbackDebugTest = false
    private lateinit var repository: InnerTubeSearchRepository
    private lateinit var files: DownloadFileStore
    private val transferMutex = Mutex()
    private val cancelLock = Any()
    @Volatile private var cancelId: Long = 0
    @Volatile private var activeCall: Call? = null
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val client by lazy { OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        repository = InnerTubeSearchRepository(this)
        files = DownloadFileStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (store == null) {
            val requestedDb = intent?.getStringExtra(EXTRA_TEST_DATABASE)
            val testDb = requestedDb?.takeIf { applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }
            testStreamUrl = testDb?.let { intent?.getStringExtra(EXTRA_TEST_STREAM_URL) }?.takeIf { value ->
                val uri = Uri.parse(value)
                uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")
            }
            testAudioStreamUrl = testDb?.let { intent?.getStringExtra(EXTRA_TEST_AUDIO_STREAM_URL) }?.takeIf { value ->
                val uri = Uri.parse(value)
                uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")
            }
            testPreparingDelayMillis = if (testDb != null) {
                intent.getLongExtra(EXTRA_TEST_PREPARING_DELAY_MS, 0L).coerceIn(0L, MAX_TEST_PREPARING_DELAY_MS)
            } else 0L
            testHoldPreparingNotification = testDb != null &&
                intent.getBooleanExtra(EXTRA_TEST_HOLD_PREPARING_NOTIFICATION, false)
            allowLoopbackDebugTest = testStreamUrl != null
            store = DownloadJobStore(this, testDb ?: "lowtube_download_queue.db").also { it.requeueInterrupted() }
        }
        val notification = buildNotification(getString(R.string.download_preparing), 0, 0, true)
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification)
        when (intent?.action) {
            ACTION_CANCEL -> intent.getLongExtra(EXTRA_ID, 0).takeIf { it > 0 }?.let(::cancel)
            ACTION_START, null -> Unit
        }
        runWorker()
        return START_STICKY
    }

    private fun runWorker() {
        val queue = store ?: return
        if (worker?.isActive == true) return
        if (queue.pending() == null) {
            stopForeground(true)
            stopSelf()
            return
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(TERMINAL_NOTIFICATION_ID)
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LowTube:DownloadQueue")
                .apply { setReferenceCounted(false); acquire(TimeUnit.HOURS.toMillis(6)) }
        }
        worker = scope.launch {
            try {
                val preparingDelay = testPreparingDelayMillis
                testPreparingDelayMillis = 0L
                if (preparingDelay > 0L) delay(preparingDelay)
                if (testHoldPreparingNotification) awaitCancellation()
                transferMutex.withLock {
                    while (true) {
                        val job = queue.pending() ?: break
                        process(job)
                    }
                }
            } finally {
                wakeLock?.takeIf { it.isHeld }?.release()
                stopForeground(false)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
                stopSelf()
            }
        }
    }

    private fun cancel(id: Long) {
        synchronized(cancelLock) {
            val queue = store ?: return
            val job = queue.get(id) ?: return
            when (job.status) {
                DownloadStatus.PENDING -> queue.update(id, DownloadStatus.CANCELLED, "Cancelled by user.")
                DownloadStatus.RUNNING -> {
                    cancelId = id
                    activeCall?.cancel()
                }
            }
        }
    }

    private suspend fun process(initial: DownloadJob) {
        val queue = store ?: return
        var job = queue.get(initial.id) ?: return
        val part = files.partialFile(job)
        runCatching { part.parentFile?.mkdirs() }
        val isMatroska = job.fileName.endsWith(".mkv", ignoreCase = true)
        val readyConverted = files.readyConvertedFile(job)
        val reuseConverted = readyConverted.isFile && readyConverted.length() > 0L
        var downloaded = if (reuseConverted) job.downloadedBytes else part.length()
        var requiresMuxing = false
        transferStartNs = System.nanoTime()
        queue.update(job.id, DownloadStatus.RUNNING, downloaded = downloaded)
        show(job, downloaded, 0, true)
        try {
            withContext(Dispatchers.IO) { files.validateDestination() }
            val completeLocalSource = job.totalBytes > 0L && job.downloadedBytes == job.totalBytes &&
                part.isFile && part.length() == job.totalBytes
            val publishLocally = reuseConverted || (completeLocalSource && !isMatroska)
            val option = if (publishLocally) null else {
                val root = JSONObject(job.videoJson)
                val video = VideoItem(root.getString("id"), root.getString("title"), root.optString("channel"), root.optString("channelId"), root.optString("published"), root.optString("duration", "—"), root.optString("kind", "video"), root.optString("liveStatus"), root.optString("thumbnailUrl"), root.getString("url"))
                // Resolve a fresh signed URL when a transfer may still be needed.
                testStreamUrl?.let { uri ->
                    val testVideoStream = ResolvedVideoStream(
                        uri = uri,
                        mimeType = job.mimeType,
                        video = video,
                        userAgent = job.userAgent,
                        durationMs = 0L,
                        isAudioOnly = job.audioOnly,
                        isMuxed = !job.audioOnly && testAudioStreamUrl == null,
                    )
                    DownloadFormatOption(
                        key = job.formatKey,
                        stream = testVideoStream,
                        container = when {
                            job.fileName.endsWith(".mkv", ignoreCase = true) -> "MKV"
                            job.fileName.endsWith(".webm", ignoreCase = true) -> "WebM"
                            job.mimeType.startsWith("audio/") -> "M4A"
                            else -> "MP4"
                        },
                        audioStream = testAudioStreamUrl?.let { audioUri ->
                            ResolvedVideoStream(
                                uri = audioUri,
                                mimeType = "audio/mp4",
                                video = video,
                                userAgent = job.userAgent,
                                durationMs = 0L,
                                audioCodec = "mp4a",
                                isAudioOnly = true,
                            )
                        },
                    )
                } ?: repository.resolveDownloadOption(video, DownloadStreamPreferences(maxHeight = 2160, h264Only = false, fps30Only = false), job.audioOnly, job.formatKey)
            }
            requiresMuxing = option?.requiresMuxing == true
            if (requiresMuxing) downloaded = files.videoTrackFile(job).length() + files.audioTrackFile(job).length()
            else if (!reuseConverted) downloaded = part.length()
            queue.update(job.id, DownloadStatus.RUNNING, downloaded = downloaded)
            show(job, downloaded, 0, true)
            val rateLimit = getSharedPreferences("lowtube_settings", MODE_PRIVATE).getInt("download_limit_kib", 0).coerceAtLeast(0)
            val outcome = if (reuseConverted) {
                TransferOutcome(job.downloadedBytes, job.totalBytes, complete = true)
            } else if (completeLocalSource && !requiresMuxing) {
                TransferOutcome(part.length(), job.totalBytes, complete = true)
            } else if (requiresMuxing) {
                val transferOption = requireNotNull(option)
                downloadMuxedTracks(job, transferOption, transferOption.stream.video, part, queue, rateLimit)
            } else {
                val stream = requireNotNull(option).stream
                val result = OkHttpDownloadTransport(client).download(
                    stream, stream.video, part, rateLimit,
                    shouldCancel = { cancelId == job.id },
                    maxBytesThisAttempt = job.maxBytesPerAttempt.takeIf { it > 0 } ?: Long.MAX_VALUE,
                    requirePartialResponse = job.maxBytesPerAttempt > 0,
                    allowLoopbackHttpForDebugTest = allowLoopbackDebugTest,
                    onCallCreated = { call -> setActiveCall(job.id, call) },
                    onProgress = { progress ->
                        downloaded = progress.downloaded
                        queue.update(job.id, DownloadStatus.RUNNING, downloaded = progress.downloaded, total = progress.total)
                        show(job, progress.downloaded, progress.total, true)
                    },
                )
                TransferOutcome(result.downloaded, result.total, result.total <= 0L || result.downloaded >= result.total)
            }
            downloaded = outcome.downloaded
            if (cancelId == job.id) throw CancellationException("Cancelled by user.")
            if (!outcome.complete && job.maxBytesPerAttempt > 0) {
                queue.update(job.id, DownloadStatus.PAUSED, "Paused after bounded transfer chunk.", outcome.downloaded, outcome.total)
                show(job, outcome.downloaded, outcome.total, false, getString(R.string.download_paused_resume))
            } else {
                queue.update(job.id, DownloadStatus.RUNNING, downloaded = outcome.downloaded, total = outcome.total)
                job = queue.get(job.id) ?: return
                val publicationPart = if (reuseConverted) {
                    readyConverted
                } else if (job.transcodeMp3) {
                    show(job, outcome.downloaded, outcome.total, true, getString(R.string.download_mp3_converting))
                    val converted = File(job.partialPath + ".mp3")
                    AudioMp3Transcoder.transcode(part, converted, job.targetAudioBitrateKbps) { cancelId == job.id }
                    if (cancelId == job.id) throw CancellationException("Cancelled by user.")
                    retainConvertedFile(converted, readyConverted)
                } else if (requiresMuxing || option?.container == "MKV") {
                    val converted = if (requiresMuxing) part else files.matroskaFile(job)
                    if (!requiresMuxing) {
                        show(job, outcome.downloaded, outcome.total, true, getString(R.string.download_preparing))
                        withContext(Dispatchers.IO) { MatroskaMuxer.muxSingle(part, converted) { cancelId == job.id } }
                    }
                    if (cancelId == job.id) throw CancellationException("Cancelled by user.")
                    retainConvertedFile(converted, readyConverted)
                } else part
                if (!job.transcodeMp3 && publicationPart == readyConverted) files.deleteTrackFiles(job)
                val published = withContext(Dispatchers.IO) { files.publish(job, publicationPart) }
                val committed = synchronized(cancelLock) {
                    if (cancelId == job.id) false
                    else {
                        queue.update(job.id, DownloadStatus.SUCCESSFUL, downloaded = outcome.downloaded, total = outcome.total, uri = published.toString())
                        true
                    }
                }
                if (!committed) {
                    files.deletePublished(published)
                    throw CancellationException("Cancelled by user.")
                }
                if (publicationPart != part) part.delete()
                show(job, outcome.downloaded, outcome.total, false, getString(R.string.download_complete))
            }
        } catch (cancelled: CancellationException) {
            downloaded = partialBytes(job, part, requiresMuxing).takeIf { it > 0L } ?: downloaded
            queue.update(job.id, DownloadStatus.CANCELLED, "Cancelled by user.", downloaded)
            show(job, downloaded, job.totalBytes, false, getString(R.string.download_cancelled))
            synchronized(cancelLock) { if (cancelId == job.id) cancelId = 0 }
        } catch (failure: Exception) {
            if (cancelId == job.id) {
                downloaded = partialBytes(job, part, requiresMuxing).takeIf { it > 0L } ?: downloaded
                queue.update(job.id, DownloadStatus.CANCELLED, "Cancelled by user.", downloaded)
                show(job, downloaded, job.totalBytes, false, getString(R.string.download_cancelled))
                synchronized(cancelLock) { if (cancelId == job.id) cancelId = 0 }
            } else {
                if (failure.message?.contains("HTTP 416") == true || failure.message?.contains("unexpected byte offset") == true) {
                    if (requiresMuxing) files.deleteTrackFiles(job) else part.delete()
                    downloaded = 0
                }
                queue.update(job.id, DownloadStatus.FAILED, failure.message ?: failure.javaClass.simpleName, downloaded)
                show(job, downloaded, job.totalBytes, false, getString(R.string.download_failed))
            }
        } finally {
            if (job.transcodeMp3) File(job.partialPath + ".mp3").delete()
            synchronized(cancelLock) { activeCall = null }
        }
    }

    private fun retainConvertedFile(converted: File, ready: File): File {
        if (!converted.renameTo(ready)) throw IOException("Could not retain the converted download.")
        return ready
    }

    private suspend fun downloadMuxedTracks(
        job: DownloadJob,
        option: DownloadFormatOption,
        video: VideoItem,
        outputPart: File,
        queue: DownloadJobStore,
        rateLimitKiB: Int,
    ): TransferOutcome {
        val audioStream = option.audioStream ?: throw IOException("The selected format has no separate audio track.")
        val videoPart = files.videoTrackFile(job)
        val audioPart = files.audioTrackFile(job)
        var videoTotal = files.trackTotal(job, video = true)
        var audioTotal = files.trackTotal(job, video = false)
        val attemptLimit = job.maxBytesPerAttempt.takeIf { it > 0L } ?: Long.MAX_VALUE
        var attemptBytes = 0L

        fun reportProgress() {
            val downloaded = videoPart.length() + audioPart.length()
            val total = if (videoTotal > 0L && audioTotal > 0L) videoTotal + audioTotal else 0L
            queue.update(job.id, DownloadStatus.RUNNING, downloaded = downloaded, total = total)
            show(job, downloaded, total, true)
        }

        suspend fun fetchTrack(stream: ResolvedVideoStream, file: File, isVideo: Boolean, limit: Long): TransferProgress {
            return OkHttpDownloadTransport(client).download(
                stream,
                video,
                file,
                rateLimitKiB,
                shouldCancel = { cancelId == job.id },
                maxBytesThisAttempt = limit,
                requirePartialResponse = job.maxBytesPerAttempt > 0L,
                allowLoopbackHttpForDebugTest = allowLoopbackDebugTest,
                onCallCreated = { call -> setActiveCall(job.id, call) },
                onProgress = { progress ->
                    if (progress.total > 0L) {
                        if (isVideo) {
                            videoTotal = progress.total
                            files.saveTrackTotal(job, video = true, videoTotal)
                        } else {
                            audioTotal = progress.total
                            files.saveTrackTotal(job, video = false, audioTotal)
                        }
                    }
                    reportProgress()
                },
            )
        }

        var videoComplete = videoTotal > 0L && videoPart.length() >= videoTotal
        if (!videoComplete) {
            val before = videoPart.length()
            val result = fetchTrack(option.stream, videoPart, isVideo = true, limit = attemptLimit)
            if (result.total > 0L) {
                videoTotal = result.total
                files.saveTrackTotal(job, video = true, videoTotal)
            }
            val added = (result.downloaded - before).coerceAtLeast(0L)
            attemptBytes += added
            videoComplete = if (videoTotal > 0L) videoPart.length() >= videoTotal
                else attemptLimit == Long.MAX_VALUE || added < attemptLimit
            if (!videoComplete) {
                reportProgress()
                return TransferOutcome(videoPart.length() + audioPart.length(), combinedTotal(videoTotal, audioTotal), false)
            }
        }

        val remaining = if (attemptLimit == Long.MAX_VALUE) Long.MAX_VALUE else (attemptLimit - attemptBytes).coerceAtLeast(0L)
        if (remaining == 0L && !(audioTotal > 0L && audioPart.length() >= audioTotal)) {
            reportProgress()
            return TransferOutcome(videoPart.length() + audioPart.length(), combinedTotal(videoTotal, audioTotal), false)
        }

        var audioComplete = audioTotal > 0L && audioPart.length() >= audioTotal
        if (!audioComplete) {
            val before = audioPart.length()
            val result = fetchTrack(audioStream, audioPart, isVideo = false, limit = remaining)
            if (result.total > 0L) {
                audioTotal = result.total
                files.saveTrackTotal(job, video = false, audioTotal)
            }
            val added = (result.downloaded - before).coerceAtLeast(0L)
            audioComplete = if (audioTotal > 0L) audioPart.length() >= audioTotal
                else remaining == Long.MAX_VALUE || added < remaining
            if (!audioComplete) {
                reportProgress()
                return TransferOutcome(videoPart.length() + audioPart.length(), combinedTotal(videoTotal, audioTotal), false)
            }
        }

        if (!videoComplete || !audioComplete) {
            reportProgress()
            return TransferOutcome(videoPart.length() + audioPart.length(), combinedTotal(videoTotal, audioTotal), false)
        }
        if (cancelId == job.id) throw CancellationException("Cancelled by user.")
        val sourceBytes = videoPart.length() + audioPart.length()
        MediaTrackMuxer.merge(videoPart, audioPart, outputPart, option.container) { cancelId == job.id }
        return TransferOutcome(
            downloaded = sourceBytes,
            total = combinedTotal(videoTotal, audioTotal),
            complete = true,
        )
    }

    private fun combinedTotal(videoTotal: Long, audioTotal: Long): Long =
        if (videoTotal > 0L && audioTotal > 0L) videoTotal + audioTotal else 0L

    private fun partialBytes(job: DownloadJob, part: File, muxing: Boolean): Long =
        if (muxing) files.videoTrackFile(job).length() + files.audioTrackFile(job).length()
        else part.takeIf { it.isFile }?.length() ?: 0L

    private fun setActiveCall(jobId: Long, call: Call?) {
        synchronized(cancelLock) {
            activeCall = call
            if (call != null && cancelId == jobId) call.cancel()
        }
    }

    private var transferStartNs = 0L
    private fun show(job: DownloadJob, downloaded: Long, total: Long, active: Boolean, message: String? = null) {
        if (transferStartNs == 0L) transferStartNs = System.nanoTime()
        val title = message ?: job.fileName
        val notification = buildNotification(title, downloaded, total, active, job.id)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (active) manager.cancel(TERMINAL_NOTIFICATION_ID)
        else manager.notify(TERMINAL_NOTIFICATION_ID, notification)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, downloaded: Long, total: Long, active: Boolean, id: Long = 0): Notification {
        return DownloadNotificationFactory.build(this, title, downloaded, total, active, id)
    }

    private fun createChannel() = DownloadNotificationFactory.createChannel(this)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { activeCall?.cancel(); wakeLock?.takeIf { it.isHeld }?.release(); scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.lowtube.android.downloads.START"
        const val ACTION_CANCEL = "com.lowtube.android.downloads.CANCEL"
        const val EXTRA_ID = "download_id"
        const val EXTRA_TEST_DATABASE = "test_database_name"
        const val EXTRA_TEST_STREAM_URL = "test_stream_url"
        const val EXTRA_TEST_AUDIO_STREAM_URL = "test_audio_stream_url"
        const val EXTRA_TEST_PREPARING_DELAY_MS = "test_preparing_delay_ms"
        const val EXTRA_TEST_HOLD_PREPARING_NOTIFICATION = "test_hold_preparing_notification"
        private const val NOTIFICATION_ID = 4207
        internal const val TERMINAL_NOTIFICATION_ID = 4208
        private const val MAX_TEST_PREPARING_DELAY_MS = 5_000L
    }
}

