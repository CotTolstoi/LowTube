package com.lowtube.android.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.lowtube.android.MainActivity
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var store: DownloadJobStore? = null
    private var testStreamUrl: String? = null
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
            allowLoopbackDebugTest = testStreamUrl != null
            store = DownloadJobStore(this, testDb ?: "lowtube_download_queue.db").also { it.requeueInterrupted() }
        }
        val notification = buildNotification("Preparing download queue", 0, 0, false)
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
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LowTube:DownloadQueue")
                .apply { setReferenceCounted(false); acquire(TimeUnit.HOURS.toMillis(6)) }
        }
        worker = scope.launch {
            try {
                transferMutex.withLock {
                    while (true) {
                        val job = queue.pending() ?: break
                        process(job)
                    }
                }
            } finally {
                wakeLock?.takeIf { it.isHeld }?.release()
                stopForeground(false)
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
        var downloaded = part.takeIf { it.isFile }?.length() ?: 0L
        transferStartNs = System.nanoTime()
        queue.update(job.id, DownloadStatus.RUNNING, downloaded = downloaded)
        show(job, downloaded, 0, true)
        try {
            val root = JSONObject(job.videoJson)
            val video = VideoItem(root.getString("id"), root.getString("title"), root.optString("channel"), root.optString("channelId"), root.optString("published"), root.optString("duration", "—"), root.optString("kind", "video"), root.optString("liveStatus"), root.optString("thumbnailUrl"), root.getString("url"))
            // Re-resolve a fresh signed URL for each run; retain the user's selected itag.
            val option = testStreamUrl?.let { uri ->
                DownloadFormatOption(
                    key = job.formatKey,
                    stream = ResolvedVideoStream(uri, job.mimeType, video, job.userAgent, 0L, isAudioOnly = job.audioOnly, isMuxed = !job.audioOnly),
                    container = job.mimeType.substringAfter('/', "test"),
                )
            } ?: repository.resolveDownloadOption(video, DownloadStreamPreferences(maxHeight = 2160, h264Only = false, fps30Only = false), job.audioOnly, job.formatKey)
            val result = OkHttpDownloadTransport(client).download(
                option.stream, video, part,
                getSharedPreferences("lowtube_settings", MODE_PRIVATE).getInt("download_limit_kib", 0).coerceAtLeast(0),
                shouldCancel = { cancelId == job.id },
                maxBytesThisAttempt = job.maxBytesPerAttempt.takeIf { it > 0 } ?: Long.MAX_VALUE,
                requirePartialResponse = job.maxBytesPerAttempt > 0,
                allowLoopbackHttpForDebugTest = allowLoopbackDebugTest,
                onCallCreated = { call ->
                    synchronized(cancelLock) {
                        activeCall = call
                        if (call != null && cancelId == job.id) call.cancel()
                    }
                },
                onProgress = { progress ->
                    downloaded = progress.downloaded
                    queue.update(job.id, DownloadStatus.RUNNING, downloaded = progress.downloaded, total = progress.total)
                    show(job, progress.downloaded, progress.total, true)
                },
            )
            downloaded = result.downloaded
            if (cancelId == job.id) throw CancellationException("Cancelled by user.")
            if (result.total > result.downloaded && job.maxBytesPerAttempt > 0) {
                queue.update(job.id, DownloadStatus.PAUSED, "Paused after bounded transfer chunk.", result.downloaded, result.total)
                show(job, result.downloaded, result.total, false, "Paused; resume to continue")
            } else {
                job = queue.get(job.id) ?: return
                val published = withContext(Dispatchers.IO) { files.publish(job) }
                val committed = synchronized(cancelLock) {
                    if (cancelId == job.id) false
                    else {
                        queue.update(job.id, DownloadStatus.SUCCESSFUL, downloaded = result.downloaded, total = result.total, uri = published.toString())
                        true
                    }
                }
                if (!committed) {
                    files.deletePublished(published)
                    throw CancellationException("Cancelled by user.")
                }
                show(job, result.downloaded, result.total, false, "Download complete")
            }
        } catch (cancelled: CancellationException) {
            downloaded = part.takeIf { it.isFile }?.length() ?: downloaded
            queue.update(job.id, DownloadStatus.CANCELLED, "Cancelled by user.", downloaded)
            show(job, downloaded, job.totalBytes, false, "Download cancelled")
            synchronized(cancelLock) { if (cancelId == job.id) cancelId = 0 }
        } catch (failure: Exception) {
            if (cancelId == job.id) {
                downloaded = part.takeIf { it.isFile }?.length() ?: downloaded
                queue.update(job.id, DownloadStatus.CANCELLED, "Cancelled by user.", downloaded)
                show(job, downloaded, job.totalBytes, false, "Download cancelled")
                synchronized(cancelLock) { if (cancelId == job.id) cancelId = 0 }
            } else {
                if (failure.message?.contains("HTTP 416") == true || failure.message?.contains("unexpected byte offset") == true) {
                    part.delete()
                    downloaded = 0
                }
                queue.update(job.id, DownloadStatus.FAILED, failure.message ?: failure.javaClass.simpleName, downloaded)
                show(job, downloaded, job.totalBytes, false, "Download failed: ${failure.message ?: "unknown error"}")
            }
        } finally {
            synchronized(cancelLock) { activeCall = null }
        }
    }

    private var transferStartNs = 0L
    private fun show(job: DownloadJob, downloaded: Long, total: Long, active: Boolean, message: String? = null) {
        if (transferStartNs == 0L) transferStartNs = System.nanoTime()
        val title = message ?: job.fileName
        val notification = buildNotification(title, downloaded, total, active, job.id)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, downloaded: Long, total: Long, active: Boolean, id: Long = 0): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("LowTube downloads").setContentText(title).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active).setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (total > 0) builder.setProgress(100, ((downloaded * 100L) / total).toInt().coerceIn(0, 100), false) else if (active) builder.setProgress(0, 0, true)
        if (active && id > 0) {
            val cancel = PendingIntent.getService(this, id.toInt(), Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Cancel", cancel)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "LowTube downloads", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { activeCall?.cancel(); wakeLock?.takeIf { it.isHeld }?.release(); scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.lowtube.android.downloads.START"
        const val ACTION_CANCEL = "com.lowtube.android.downloads.CANCEL"
        const val EXTRA_ID = "download_id"
        const val EXTRA_TEST_DATABASE = "test_database_name"
        const val EXTRA_TEST_STREAM_URL = "test_stream_url"
        private const val NOTIFICATION_ID = 4207
        private const val CHANNEL_ID = "lowtube_downloads"
    }
}

