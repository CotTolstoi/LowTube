package com.lowtube.android.downloads

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.AppLocale
import com.lowtube.android.R
import com.lowtube.android.data.DownloadJobStore
import com.lowtube.android.data.DownloadStreamPreferences
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.model.DownloadStatus
import com.lowtube.android.model.ResolvedVideoStream
import com.lowtube.android.model.VideoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import okhttp3.OkHttpClient
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in end-to-end foreground job check. Each service run is capped at 64 KiB; no playback or full file. */
@RunWith(AndroidJUnit4::class)
class DownloadServiceAcceptanceTest {
    @Test
    fun boundedTransportAcceptsResolvedNQueryParameter() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val video = VideoItem("fixture", "Resolved n fixture", duration = "—", url = "https://www.youtube.com/watch?v=fixture")
            val server = RangeResumeLoopbackServer()
            val partial = File(context.cacheDir, "resolved-n-${System.currentTimeMillis()}.part")
            val stream = ResolvedVideoStream(
                uri = "${server.url}?n=already-transformed",
                mimeType = "video/mp4",
                video = video,
                userAgent = "LowTube bounded URL test",
                durationMs = 0L,
                isMuxed = true,
            )
            try {
                server.start()
                val result = OkHttpDownloadTransport(OkHttpClient()).download(
                    stream = stream,
                    video = video,
                    partialFile = partial,
                    rateLimitKiB = 0,
                    shouldCancel = { false },
                    maxBytesThisAttempt = 16L,
                    requirePartialResponse = true,
                    allowLoopbackHttpForDebugTest = true,
                )

                assertEquals(16L, result.downloaded)
                assertEquals("bytes=0-15", server.rangeHeader)
                assertTrue(server.awaitClientClosed())
            } finally {
                server.close()
                partial.delete()
            }
        }
    }

    @Test
    fun muxedDownloadResumesSeparateVideoAndAudioRangesThenPublishesMp4() {
        runBlocking {
            assumeTrue(
                "Muxed track service acceptance is opt-in; pass -e runMuxedDownloadPairAcceptance true",
                InstrumentationRegistry.getArguments().getString("runMuxedDownloadPairAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val storagePermissionAvailable = Build.VERSION.SDK_INT > 28 ||
                (context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            assumeTrue("Android 6 publication acceptance requires already-granted READ/WRITE storage permission", storagePermissionAvailable)

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val videoBytes = instrumentation.context.assets.open("com/lowtube/android/downloads/mux_video_track.mp4").use { it.readBytes() }
            val audioBytes = instrumentation.context.assets.open("com/lowtube/android/downloads/mux_audio_track.mp4").use { it.readBytes() }
            val videoServer = RangeResumeLoopbackServer(videoBytes.size.toLong(), MAX_TRACK_RANGE_REQUESTS, videoBytes)
            val audioServer = RangeResumeLoopbackServer(audioBytes.size.toLong(), MAX_TRACK_RANGE_REQUESTS, audioBytes)
            val dbName = "lowtube-mux-download-${System.currentTimeMillis()}.db"
            val partial = File(context.cacheDir, "$dbName.part")
            val remuxed = File(context.cacheDir, "$dbName-output.mp4")
            val store = DownloadJobStore(context, dbName)
            var id = 0L
            var publishedUri: Uri? = null
            val video = VideoItem("fixture", "Muxed track fixture", duration = "1", url = "https://www.youtube.com/watch?v=fixture")
            val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
            try {
                id = store.insert(
                    videoJson,
                    "bounded-mux-pair-test",
                    false,
                    "lowtube-mux-${System.currentTimeMillis()}.mp4",
                    "video/mp4",
                    "LowTube muxed track acceptance",
                    partial.absolutePath,
                    maxBytesPerAttempt = TRACK_RANGE_CHUNK_BYTES,
                )
                videoServer.start()
                audioServer.start()

                var result = store.get(id) ?: error("Muxed test job disappeared before it started.")
                for (attempt in 0 until MAX_TRACK_RANGE_REQUESTS) {
                    val bytesBeforeAttempt = File(partial.absolutePath + ".video").length() + File(partial.absolutePath + ".audio").length()
                    startService(context, dbName, videoServer.url, audioServer.url)
                    result = waitForTerminalAttempt(store, id)
                    if (result.status == DownloadStatus.SUCCESSFUL) break
                    assertEquals("Muxed track transfer failed: ${result.reason}", DownloadStatus.PAUSED, result.status)
                    waitForServiceStopped(context)
                    val videoPartialBytes = File(partial.absolutePath + ".video").length()
                    val audioPartialBytes = File(partial.absolutePath + ".audio").length()
                    assertEquals(videoPartialBytes + audioPartialBytes, result.downloadedBytes)
                    assertTrue("One attempt exceeded the bounded two-track cap", result.downloadedBytes - bytesBeforeAttempt <= TRACK_RANGE_CHUNK_BYTES)
                    if (result.status != DownloadStatus.SUCCESSFUL) store.update(id, DownloadStatus.PENDING)
                }

                assertEquals("Two-track service did not finish within the bounded retry budget: ${result.reason}", DownloadStatus.SUCCESSFUL, result.status)
                val resultUri = Uri.parse(result.localUri)
                publishedUri = resultUri
                context.contentResolver.openInputStream(resultUri)?.use { input -> remuxed.outputStream().use(input::copyTo) }
                    ?: error("Published muxed MP4 could not be opened.")
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(remuxed.absolutePath)
                    assertEquals(2, extractor.trackCount)
                    val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.toSet()
                    assertEquals(setOf("video/avc", "audio/mp4a-latm"), mimes)
                } finally {
                    extractor.release()
                }
                assertTrue("Video Range requests did not resume from the saved offset: ${videoServer.rangeHeaders}", rangeHeadersAreSequential(videoServer.rangeHeaders, videoBytes.size.toLong()))
                assertTrue("Audio Range requests did not resume from the saved offset: ${audioServer.rangeHeaders}", rangeHeadersAreSequential(audioServer.rangeHeaders, audioBytes.size.toLong()))
                assertFalse("Private video track partial remained after success", File(partial.absolutePath + ".video").exists())
                assertFalse("Private audio track partial remained after success", File(partial.absolutePath + ".audio").exists())
                waitForServiceStopped(context)
                DownloadFileStore(context).deletePublished(resultUri)
                publishedUri = null
                Log.i(TAG, "muxedTrackDownloadStatus=${result.status}, videoBytes=${videoBytes.size}, audioBytes=${audioBytes.size}, rangeRequests=${videoServer.rangeHeaders.size}/${audioServer.rangeHeaders.size}")
            } finally {
                context.stopService(Intent(context, DownloadService::class.java))
                videoServer.close()
                audioServer.close()
                partial.delete()
                File(partial.absolutePath + ".video").delete()
                File(partial.absolutePath + ".audio").delete()
                File(partial.absolutePath + ".video.total").delete()
                File(partial.absolutePath + ".audio.total").delete()
                remuxed.delete()
                publishedUri?.let { runCatching { DownloadFileStore(context).deletePublished(it) } }
                if (id > 0L) store.remove(id)
                store.close()
                context.deleteDatabase(dbName)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            }
        }
    }

    @Test
    fun cancellingMuxedDownloadDuringAudioRangeKeepsPartialsForResume() {
        runBlocking {
            assumeTrue(
                "Muxed cancellation acceptance is opt-in; pass -e runMuxedDownloadCancelAcceptance true",
                InstrumentationRegistry.getArguments().getString("runMuxedDownloadCancelAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val videoBytes = instrumentation.context.assets.open("com/lowtube/android/downloads/mux_video_track.mp4").use { it.readBytes() }
            val audioBytes = instrumentation.context.assets.open("com/lowtube/android/downloads/mux_audio_track.mp4").use { it.readBytes() }
            val dbName = "lowtube-mux-cancel-${System.currentTimeMillis()}.db"
            val partial = File(context.cacheDir, "$dbName.part")
            val videoPartial = File(partial.absolutePath + ".video")
            val audioPartial = File(partial.absolutePath + ".audio")
            val audioTotalFile = File(partial.absolutePath + ".audio.total")
            val store = DownloadJobStore(context, dbName)
            val preferences = context.getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
            val previousRateLimit = preferences.getInt("download_limit_kib", 0)
            val server = SlowPartialRangeServer(audioBytes)
            val remuxed = File(context.cacheDir, "$dbName-resumed.mp4")
            var id = 0L
            var retryServer: RangeResumeLoopbackServer? = null
            var publishedUri: Uri? = null
            val video = VideoItem("fixture", "Muxed cancellation fixture", duration = "1", url = "https://www.youtube.com/watch?v=fixture")
            val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
            try {
                id = store.insert(
                    videoJson,
                    "bounded-mux-cancel-test",
                    false,
                    "lowtube-mux-cancel-${System.currentTimeMillis()}.mp4",
                    "video/mp4",
                    "LowTube muxed cancellation acceptance",
                    partial.absolutePath,
                    maxBytesPerAttempt = TRACK_RANGE_CHUNK_BYTES,
                )
                videoPartial.writeBytes(videoBytes)
                File(partial.absolutePath + ".video.total").writeText(videoBytes.size.toString())
                preferences.edit().putInt("download_limit_kib", 1).commit()
                server.start()

                startService(context, dbName, server.url, server.url)
                waitForBytesWritten(store, id, audioPartial)
                assertEquals("The service should finish the seeded video before fetching audio", videoBytes.size.toLong(), videoPartial.length())
                assertTrue("Cancellation fixture must stop during the incomplete audio transfer", audioPartial.length() in 1..server.prefixBytes.toLong())
                cancelService(context, dbName, id)
                val cancelled = withTimeoutPoll {
                    val job = store.get(id) ?: error("Cancelled muxed download disappeared.")
                    if (job.status == DownloadStatus.CANCELLED || job.status == DownloadStatus.FAILED) job else null
                }
                assertEquals("Muxed job did not enter CANCELLED: ${cancelled.reason}", DownloadStatus.CANCELLED, cancelled.status)
                assertEquals("Completed video track should be retained", videoBytes.size.toLong(), videoPartial.length())
                assertTrue("Downloaded audio prefix should be retained for retry", audioPartial.length() in 1..server.prefixBytes.toLong())
                assertEquals("The persisted byte count should include both tracks", videoPartial.length() + audioPartial.length(), cancelled.downloadedBytes)
                assertEquals("bytes=0-${TRACK_RANGE_CHUNK_BYTES - 1}", server.rangeHeader)
                assertEquals(audioBytes.size.toLong(), audioTotalFile.readText().toLong())
                assertTrue("OkHttp cancellation did not close the active audio response", server.awaitClientClosed())
                waitForServiceStopped(context)
                Log.i(TAG, "muxedCancelStatus=${cancelled.status}, videoBytes=${videoPartial.length()}, audioPartialBytes=${audioPartial.length()}")

                if (Build.VERSION.SDK_INT <= 28) return@runBlocking
                preferences.edit().putInt("download_limit_kib", previousRateLimit).commit()
                val resumeServer = RangeResumeLoopbackServer(audioBytes.size.toLong(), MAX_TRACK_RANGE_REQUESTS, audioBytes)
                retryServer = resumeServer
                resumeServer.start()
                store.update(id, DownloadStatus.PENDING)
                startService(context, dbName, server.url, resumeServer.url)
                val resumed = waitForTerminalAttempt(store, id)
                assertEquals("Cancelled muxed partial did not complete on retry: ${resumed.reason}", DownloadStatus.SUCCESSFUL, resumed.status)
                assertEquals("Retry did not continue at the saved audio offset", "bytes=${server.prefixBytes}-${TRACK_RANGE_CHUNK_BYTES + server.prefixBytes - 1}", resumeServer.rangeHeader)
                val resultUri = Uri.parse(resumed.localUri)
                publishedUri = resultUri
                context.contentResolver.openInputStream(resultUri)?.use { input -> remuxed.outputStream().use(input::copyTo) }
                    ?: error("Retried muxed MP4 could not be opened.")
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(remuxed.absolutePath)
                    val mimeTypes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.toSet()
                    assertEquals(setOf("video/avc", "audio/mp4a-latm"), mimeTypes)
                } finally {
                    extractor.release()
                }
                assertEquals(videoBytes.size.toLong() + audioBytes.size, resumed.downloadedBytes)
                assertFalse("Video partial should be removed after successful retry", videoPartial.exists())
                assertFalse("Audio partial should be removed after successful retry", audioPartial.exists())
                waitForServiceStopped(context)
                DownloadFileStore(context).deletePublished(resultUri)
                publishedUri = null
                Log.i(TAG, "muxedCancelResumeStatus=${resumed.status}, resumedAudioRange=${resumeServer.rangeHeader}")
            } finally {
                context.stopService(Intent(context, DownloadService::class.java))
                server.close()
                retryServer?.close()
                preferences.edit().putInt("download_limit_kib", previousRateLimit).commit()
                partial.delete()
                videoPartial.delete()
                audioPartial.delete()
                remuxed.delete()
                File(partial.absolutePath + ".video.total").delete()
                audioTotalFile.delete()
                File(partial.absolutePath + ".video.total.tmp").delete()
                File(partial.absolutePath + ".audio.total.tmp").delete()
                publishedUri?.let { runCatching { DownloadFileStore(context).deletePublished(it) } }
                if (id > 0L) store.remove(id)
                store.close()
                context.deleteDatabase(dbName)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            }
        }
    }

    @Test
    fun foregroundJobPausesAtRangeCapAndResumesFromPartialOffset() {
        runBlocking {
            assumeTrue(
                "Service acceptance is opt-in; pass -e runDownloadServiceAcceptance true",
                InstrumentationRegistry.getArguments().getString("runDownloadServiceAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbName = "lowtube-service-acceptance-${System.currentTimeMillis()}.db"
            val partial = File(context.cacheDir, "$dbName.part")
            val server = RangeResumeLoopbackServer(resourceBytes = CHUNK_BYTES * 4, expectedRequests = 2)
            val store = DownloadJobStore(context, dbName)
            val video = VideoItem("fixture", "Bounded resume fixture", duration = "—", url = "https://www.youtube.com/watch?v=fixture")
            val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
            val id = store.insert(
                videoJson,
                "bounded-resume-test",
                false,
                "bounded-fixture.mp4",
                "video/mp4",
                "LowTube acceptance test",
                partial.absolutePath,
                CHUNK_BYTES,
            )
            try {
                server.start()
                startService(context, dbName, testStreamUrl = server.url)
                val first = waitForTerminalAttempt(store, id)
                assertEquals("First bounded request did not pause: ${first.reason}", DownloadStatus.PAUSED, first.status)
                assertEquals(CHUNK_BYTES, first.downloadedBytes)
                assertEquals(CHUNK_BYTES, partial.length())
                waitForServiceStopped(context)

                store.update(id, DownloadStatus.PENDING)
                startService(context, dbName, testStreamUrl = server.url)
                val resumed = waitForTerminalAttempt(store, id)
                assertEquals("Resumed bounded request did not pause: ${resumed.reason}", DownloadStatus.PAUSED, resumed.status)
                assertEquals(CHUNK_BYTES * 2, resumed.downloadedBytes)
                assertEquals(CHUNK_BYTES * 2, partial.length())
                assertEquals(
                    listOf("bytes=0-${CHUNK_BYTES - 1}", "bytes=${CHUNK_BYTES}-${CHUNK_BYTES * 2 - 1}"),
                    synchronized(server.rangeHeaders) { server.rangeHeaders.toList() },
                )
                assertTrue("The service test must leave only a bounded partial file", partial.length() <= CHUNK_BYTES * 2)
                assertTrue("Both bounded requests did not close", server.awaitClientClosed())
                waitForServiceStopped(context)
                Log.i(TAG, "serviceResumeStatus=${resumed.status}, bytes=${resumed.downloadedBytes}, total=${resumed.totalBytes}")
            } finally {
                context.stopService(Intent(context, DownloadService::class.java))
                server.close()
                partial.delete()
                store.remove(id)
                store.close()
                context.deleteDatabase(dbName)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            }
        }
    }

    @Test
    fun downloadNotificationPayloadUsesSelectedAppLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalLanguage = AppLocale.language(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            for (language in listOf("en", "uk", "ru")) {
                AppLocale.setLanguage(context, language)
                val localized = AppLocale.wrap(context)
                DownloadNotificationFactory.createChannel(localized)

                val preparingText = localized.getString(R.string.download_preparing)
                val preparing = DownloadNotificationFactory.build(localized, preparingText, 0, 0, false)
                assertEquals("Preparing title failed for $language", localized.getString(R.string.downloads), preparing.extras.getCharSequence(Notification.EXTRA_TITLE))
                assertEquals("Preparing text failed for $language", preparingText, preparing.extras.getCharSequence(Notification.EXTRA_TEXT))
                assertTrue("Preparing notice must not show actions for $language", preparing.actions.orEmpty().isEmpty())

                val active = DownloadNotificationFactory.build(localized, "fixture.mp4", 16, 64, true, 57L)
                assertEquals("Cancel action is not localized for $language", localized.getString(R.string.cancel_download), active.actions.single().title)
                assertTrue("Active notice must remain ongoing for $language", active.flags and Notification.FLAG_ONGOING_EVENT != 0)

                val terminalText = localized.getString(R.string.download_failed)
                val terminal = DownloadNotificationFactory.build(localized, terminalText, 16, 64, false, 57L)
                assertEquals("Terminal text failed for $language", terminalText, terminal.extras.getCharSequence(Notification.EXTRA_TEXT))
                assertTrue("Terminal notice must not show actions for $language", terminal.actions.orEmpty().isEmpty())
                assertEquals("Terminal notice must be dismissible for $language", 0, terminal.flags and Notification.FLAG_ONGOING_EVENT)

                if (Build.VERSION.SDK_INT >= 26) {
                    assertEquals("Channel name failed for $language", localized.getString(R.string.downloads), manager.getNotificationChannel(DownloadNotificationFactory.CHANNEL_ID)?.name)
                }
            }
        } finally {
            AppLocale.setLanguage(context, originalLanguage)
            val restored = AppLocale.wrap(context)
            DownloadNotificationFactory.createChannel(restored)
        }
    }

    @Test
    fun pausedTransferRetainsLocalizedTerminalNotification() {
        runBlocking {
            assumeTrue(
                "Paused notification acceptance is opt-in; pass -e runDownloadPausedNotificationAcceptance true",
                InstrumentationRegistry.getArguments().getString("runDownloadPausedNotificationAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val originalLanguage = AppLocale.language(context)
            try {
                for (language in listOf("en", "uk", "ru")) {
                    AppLocale.setLanguage(context, language)
                    val dbName = "lowtube-paused-notification-$language-${System.currentTimeMillis()}.db"
                    val partial = File(context.cacheDir, "$dbName.part")
                    val server = RangeResumeLoopbackServer(resourceBytes = 128L)
                    val store = DownloadJobStore(context, dbName)
                    val video = VideoItem("fixture", "Pause notification fixture", duration = "—", url = "https://www.youtube.com/watch?v=fixture")
                    val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
                    val chunkBytes = 16L
                    val id = store.insert(
                        videoJson,
                        "pause-notification-test",
                        false,
                        "pause-fixture.mp4",
                        "video/mp4",
                        "LowTube acceptance test",
                        partial.absolutePath,
                        maxBytesPerAttempt = chunkBytes,
                    )
                    try {
                        server.start()
                        startService(context, dbName, testStreamUrl = server.url)
                        val paused = waitForTerminalAttempt(store, id)
                        assertEquals("Pause status failed for $language", DownloadStatus.PAUSED, paused.status)
                        assertEquals(chunkBytes, paused.downloadedBytes)
                        assertEquals(chunkBytes, partial.length())
                        assertTrue("Pause fixture did not receive the bounded Range request", server.awaitClientClosed())
                        waitForServiceStopped(context)
                        assertTerminalNotificationText(context, R.string.download_paused_resume)
                    } finally {
                        context.stopService(Intent(context, DownloadService::class.java))
                        server.close()
                        partial.delete()
                        store.remove(id)
                        store.close()
                        context.deleteDatabase(dbName)
                        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notifications.cancel(NOTIFICATION_ID)
                        notifications.cancel(DownloadService.TERMINAL_NOTIFICATION_ID)
                    }
                }
            } finally {
                AppLocale.setLanguage(context, originalLanguage)
                AppLocale.wrap(context)
            }
        }
    }

    @Test
    fun serviceColdStartRequeuesPersistedRunningJobAndResumesPartialRange() {
        runBlocking {
            assumeTrue(
                "Service recovery acceptance is opt-in; pass -e runDownloadServiceRecoveryAcceptance true",
                InstrumentationRegistry.getArguments().getString("runDownloadServiceRecoveryAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbName = "lowtube-recovery-acceptance-${System.currentTimeMillis()}.db"
            val partial = File(context.cacheDir, "$dbName.part")
            val server = RangeResumeLoopbackServer()
            val seedStore = DownloadJobStore(context, dbName)
            var monitorStore: DownloadJobStore? = null
            var id = 0L
            val prefix = ByteArray(PREFIX_BYTES) { (0x41 + it).toByte() }
            try {
                val video = VideoItem("fixture", "Recovery fixture", duration = "—", url = "https://www.youtube.com/watch?v=fixture")
                val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
                id = seedStore.insert(
                    videoJson,
                    "resume-loopback-test",
                    false,
                    "recovery-bounded.mp4",
                    "video/mp4",
                    "LowTube recovery acceptance",
                    partial.absolutePath,
                    maxBytesPerAttempt = RESUME_CHUNK_BYTES,
                )
                partial.writeBytes(prefix)
                seedStore.update(id, DownloadStatus.RUNNING, downloaded = prefix.size.toLong(), total = RangeResumeLoopbackServer.TOTAL_BYTES)
                seedStore.close()
                val observer = DownloadJobStore(context, dbName)
                monitorStore = observer

                server.start()
                startService(context, dbName, testStreamUrl = server.url)
                val resumed = waitForTerminalAttempt(observer, id)
                assertEquals("Persisted RUNNING job was not requeued: ${resumed.reason}", DownloadStatus.PAUSED, resumed.status)
                assertEquals(PREFIX_BYTES + RESUME_CHUNK_BYTES, resumed.downloadedBytes)
                assertEquals(PREFIX_BYTES + RESUME_CHUNK_BYTES, partial.length())
                assertEquals(RangeResumeLoopbackServer.TOTAL_BYTES, resumed.totalBytes)
                assertEquals("bytes=$PREFIX_BYTES-${PREFIX_BYTES + RESUME_CHUNK_BYTES - 1}", server.rangeHeader)
                assertTrue("Existing partial bytes were overwritten", prefix.contentEquals(partial.readBytes().copyOf(prefix.size)))
                assertTrue("Resume server did not observe the client close", server.awaitClientClosed())
                waitForServiceStopped(context)
                Log.i(TAG, "serviceRecoveryStatus=${resumed.status}, bytes=${resumed.downloadedBytes}, range=${server.rangeHeader}")
            } finally {
                context.stopService(Intent(context, DownloadService::class.java))
                server.close()
                partial.delete()
                monitorStore?.close()
                seedStore.close()
                val cleanup = DownloadJobStore(context, dbName)
                if (id > 0) cleanup.remove(id)
                cleanup.close()
                context.deleteDatabase(dbName)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            }
        }
    }

    @Test
    fun seedInterruptedJobForExternalProcessDeathAcceptance() {
        assumeTrue(
            "Process-death seed is opt-in; pass -e runDownloadProcessDeathSeed true",
            InstrumentationRegistry.getArguments().getString("runDownloadProcessDeathSeed") == "true",
        )
        val dbName = processDeathDatabaseName()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DownloadJobStore(context, dbName)
        val partial = File(context.cacheDir, "$dbName.part")
        try {
            assertTrue("Process-death fixture database must start empty", store.all().isEmpty())
            val video = VideoItem("fixture", "Process-death fixture", duration = "—", url = "https://www.youtube.com/watch?v=fixture")
            val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
            val id = store.insert(
                videoJson,
                "process-death-loopback-test",
                false,
                "process-death-bounded.mp4",
                "video/mp4",
                "LowTube process-death acceptance",
                partial.absolutePath,
                maxBytesPerAttempt = PROCESS_DEATH_RESUME_BYTES,
            )
            partial.writeBytes(processDeathPrefix())
            store.update(id, DownloadStatus.RUNNING, downloaded = PROCESS_DEATH_PREFIX_BYTES.toLong(), total = RangeResumeLoopbackServer.TOTAL_BYTES)
            val seeded = store.get(id) ?: error("Persisted process-death fixture disappeared.")
            assertEquals(DownloadStatus.RUNNING, seeded.status)
            assertEquals(PROCESS_DEATH_PREFIX_BYTES.toLong(), seeded.downloadedBytes)
            assertEquals(PROCESS_DEATH_PREFIX_BYTES, partial.length().toInt())
            Log.i(TAG, "processDeathSeed; db=$dbName; id=$id; prefixBytes=${partial.length()}")
        } finally {
            store.close()
        }
    }

    @Test
    fun resumesPersistedPartialAfterExternalProcessDeath() {
        runBlocking {
            assumeTrue(
                "Process-death recovery is opt-in; pass -e runDownloadProcessDeathRecovery true",
                InstrumentationRegistry.getArguments().getString("runDownloadProcessDeathRecovery") == "true",
            )
            val dbName = processDeathDatabaseName()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val partial = File(context.cacheDir, "$dbName.part")
            val store = DownloadJobStore(context, dbName)
            val server = RangeResumeLoopbackServer()
            try {
                val seededJobs = store.all()
                assertEquals("Expected exactly one durable fixture after force-stop", 1, seededJobs.size)
                val seeded = seededJobs.single()
                assertEquals("The fixture must remain RUNNING across process death", DownloadStatus.RUNNING, seeded.status)
                assertEquals(PROCESS_DEATH_PREFIX_BYTES.toLong(), seeded.downloadedBytes)
                assertTrue("Partial file was not durable across process death", partial.isFile)
                assertTrue(processDeathPrefix().contentEquals(partial.readBytes()))

                server.start()
                startService(context, dbName, testStreamUrl = server.url)
                val recovered = waitForTerminalAttempt(store, seeded.id)
                assertEquals("Process-death requeue did not pause the resumed transfer: ${recovered.reason}", DownloadStatus.PAUSED, recovered.status)
                assertEquals(PROCESS_DEATH_PREFIX_BYTES + PROCESS_DEATH_RESUME_BYTES, recovered.downloadedBytes)
                assertEquals(PROCESS_DEATH_PREFIX_BYTES + PROCESS_DEATH_RESUME_BYTES, partial.length())
                assertEquals("bytes=$PROCESS_DEATH_PREFIX_BYTES-${PROCESS_DEATH_PREFIX_BYTES + PROCESS_DEATH_RESUME_BYTES - 1}", server.rangeHeader)
                val expectedBytes = processDeathPrefix() + ByteArray(PROCESS_DEATH_RESUME_BYTES.toInt()) { (PROCESS_DEATH_PREFIX_BYTES + it).toByte() }
                assertTrue("Recovered file bytes differ from the persisted prefix and requested range", expectedBytes.contentEquals(partial.readBytes()))
                assertTrue("Process-death recovery server did not observe client close", server.awaitClientClosed())
                waitForServiceStopped(context)
                Log.i(TAG, "processDeathRecovery; bytes=${recovered.downloadedBytes}; range=${server.rangeHeader}")
            } finally {
                context.stopService(Intent(context, DownloadService::class.java))
                server.close()
                partial.delete()
                store.close()
                context.deleteDatabase(dbName)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            }
        }
    }

    @Test
    fun cleanupExternalProcessDeathAcceptanceFixture() {
        assumeTrue(
            "Process-death cleanup is opt-in; pass -e runDownloadProcessDeathCleanup true",
            InstrumentationRegistry.getArguments().getString("runDownloadProcessDeathCleanup") == "true",
        )
        val dbName = processDeathDatabaseName()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, DownloadService::class.java))
        File(context.cacheDir, "$dbName.part").delete()
        context.deleteDatabase(dbName)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    @Test
    fun configuredDownloadRateLimitIsAppliedToBoundedServiceTransfer() {
        runBlocking {
            assumeTrue(
                "Rate-limit acceptance is opt-in; pass -e runDownloadRateLimitAcceptance true",
                InstrumentationRegistry.getArguments().getString("runDownloadRateLimitAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbName = "lowtube-rate-limit-acceptance-${System.currentTimeMillis()}.db"
            val partial = File(context.cacheDir, "$dbName.part")
            val server = RangeResumeLoopbackServer(
                resourceBytes = RATE_LIMIT_CHUNK_BYTES * 4,
                expectedRequests = 2,
            )
            val store = DownloadJobStore(context, dbName)
            val preferences = context.getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
            val previousLimit = preferences.getInt("download_limit_kib", 0)
            val video = VideoItem("fixture", "Rate limit fixture", duration = "—", url = "https://www.youtube.com/watch?v=fixture")
            val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
            var id = 0L
            try {
                preferences.edit().putInt("download_limit_kib", 1).commit()
                id = store.insert(
                    videoJson,
                    "rate-limit-loopback-test",
                    false,
                    "rate-limit-bounded.mp4",
                    "video/mp4",
                    "LowTube rate-limit acceptance",
                    partial.absolutePath,
                    maxBytesPerAttempt = RATE_LIMIT_CHUNK_BYTES,
                )
                server.start()
                val firstStartedNs = System.nanoTime()
                startService(context, dbName, testStreamUrl = server.url)
                val first = waitForTerminalAttempt(store, id)
                val firstElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstStartedNs)
                assertEquals("First rate-limited attempt did not pause: ${first.reason}", DownloadStatus.PAUSED, first.status)
                assertEquals(RATE_LIMIT_CHUNK_BYTES, first.downloadedBytes)
                assertEquals(RATE_LIMIT_CHUNK_BYTES, partial.length())
                assertTrue("1 KiB/s cap did not apply to first attempt ($firstElapsedMs ms)", firstElapsedMs >= RATE_LIMIT_MIN_EXPECTED_MS)
                waitForServiceStopped(context)

                store.update(id, DownloadStatus.PENDING)
                val retryStartedNs = System.nanoTime()
                startService(context, dbName, testStreamUrl = server.url)
                val retried = waitForTerminalAttempt(store, id)
                val retryElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - retryStartedNs)
                assertEquals("Rate-limited retry did not pause: ${retried.reason}", DownloadStatus.PAUSED, retried.status)
                assertEquals(RATE_LIMIT_CHUNK_BYTES * 2, retried.downloadedBytes)
                assertEquals(RATE_LIMIT_CHUNK_BYTES * 2, partial.length())
                assertTrue("1 KiB/s cap did not apply to retry ($retryElapsedMs ms)", retryElapsedMs >= RATE_LIMIT_MIN_EXPECTED_MS)
                assertTrue("Both bounded requests did not close", server.awaitClientClosed())
                assertEquals(
                    listOf(
                        "bytes=0-${RATE_LIMIT_CHUNK_BYTES - 1}",
                        "bytes=${RATE_LIMIT_CHUNK_BYTES}-${RATE_LIMIT_CHUNK_BYTES * 2 - 1}",
                    ),
                    synchronized(server.rangeHeaders) { server.rangeHeaders.toList() },
                )
                waitForServiceStopped(context)
                Log.i(TAG, "rateLimitAcceptance; limitKiB=1; bytes=${retried.downloadedBytes}; firstElapsedMs=$firstElapsedMs; retryElapsedMs=$retryElapsedMs")
            } finally {
                context.stopService(Intent(context, DownloadService::class.java))
                server.close()
                partial.delete()
                if (id > 0) store.remove(id)
                store.close()
                context.deleteDatabase(dbName)
                preferences.edit().putInt("download_limit_kib", previousLimit).commit()
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            }
        }
    }

    @Test
    fun servicePublishesCompleteBoundedFileAndRemovesPartial() {
        runBlocking {
            assumeTrue(
                "Download publication acceptance is opt-in; pass -e runDownloadPublicationAcceptance true",
                InstrumentationRegistry.getArguments().getString("runDownloadPublicationAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val storagePermissionAvailable = Build.VERSION.SDK_INT > 28 ||
                (context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            assumeTrue("READ/WRITE_EXTERNAL_STORAGE must already be granted for API23 publication acceptance", storagePermissionAvailable)
            val dbName = "lowtube-publication-acceptance-${System.currentTimeMillis()}.db"
            val partial = File(context.cacheDir, "$dbName.part")
            val server = RangeResumeLoopbackServer()
            val store = DownloadJobStore(context, dbName)
            var id = 0L
            var publishedUri: Uri? = null
            val publishedName = "lowtube-bounded-${System.currentTimeMillis()}.mp4"
            try {
                val video = VideoItem("fixture", "Publication fixture", duration = "—", url = "https://www.youtube.com/watch?v=fixture")
                val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
                id = store.insert(
                    videoJson,
                    "publication-loopback-test",
                    false,
                    publishedName,
                    "video/mp4",
                    "LowTube publication acceptance",
                    partial.absolutePath,
                    maxBytesPerAttempt = RangeResumeLoopbackServer.TOTAL_BYTES,
                )
                server.start()
                startService(context, dbName, testStreamUrl = server.url)
                val complete = waitForTerminalAttempt(store, id)
                assertEquals("Bounded file did not reach SUCCESSFUL: ${complete.reason}", DownloadStatus.SUCCESSFUL, complete.status)
                assertEquals(RangeResumeLoopbackServer.TOTAL_BYTES, complete.downloadedBytes)
                assertEquals(RangeResumeLoopbackServer.TOTAL_BYTES, complete.totalBytes)
                assertEquals("bytes=0-${RangeResumeLoopbackServer.TOTAL_BYTES - 1}", server.rangeHeader)
                assertFalse("Private partial should be removed after publishing", partial.exists())
                val resultUri = Uri.parse(complete.localUri)
                publishedUri = resultUri
                val publishedBytes = if (resultUri.scheme == "file") {
                    File(resultUri.path.orEmpty()).readBytes()
                } else {
                    context.contentResolver.openInputStream(resultUri)?.use { it.readBytes() }
                        ?: error("Published content URI could not be opened.")
                }
                val expectedBytes = ByteArray(RangeResumeLoopbackServer.TOTAL_BYTES.toInt()) { it.toByte() }
                assertTrue("Published file contents differ from the bounded source", expectedBytes.contentEquals(publishedBytes))
                assertTrue("Publication server did not observe the client close", server.awaitClientClosed())
                waitForServiceStopped(context)
                assertTerminalNotificationText(context, R.string.download_complete)
                DownloadFileStore(context).deletePublished(resultUri)
                val stillAvailable = runCatching {
                    if (resultUri.scheme == "file") File(resultUri.path.orEmpty()).exists()
                    else context.contentResolver.openInputStream(resultUri)?.use { true } ?: false
                }.getOrDefault(false)
                assertFalse("Published file remained available after cleanup", stillAvailable)
                publishedUri = null
                Log.i(TAG, "servicePublicationStatus=${complete.status}, bytes=${complete.downloadedBytes}, uriScheme=${resultUri.scheme}")
            } finally {
                context.stopService(Intent(context, DownloadService::class.java))
                server.close()
                partial.delete()
                publishedUri?.let { runCatching { DownloadFileStore(context).deletePublished(it) } }
                if (id > 0) store.remove(id)
                store.close()
                context.deleteDatabase(dbName)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            }
        }
    }

    @Test
    fun failedTransferRetainsLocalizedTerminalNotification() {
        runBlocking {
            assumeTrue(
                "Failure notification acceptance is opt-in; pass -e runDownloadFailureNotificationAcceptance true",
                InstrumentationRegistry.getArguments().getString("runDownloadFailureNotificationAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dbName = "lowtube-failure-notification-${System.currentTimeMillis()}.db"
            val partial = File(context.cacheDir, "$dbName.part")
            val server = FailingResponseServer()
            val store = DownloadJobStore(context, dbName)
            val video = VideoItem("fixture", "Failure notification fixture", duration = "—", url = "https://www.youtube.com/watch?v=fixture")
            val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
            val id = store.insert(videoJson, "failure-notification-test", false, "failure-fixture.mp4", "video/mp4", "LowTube acceptance test", partial.absolutePath)
            try {
                server.start()
                startService(context, dbName, testStreamUrl = server.url)
                val failed = waitForTerminalAttempt(store, id)
                assertEquals("HTTP failure did not mark the bounded job failed", DownloadStatus.FAILED, failed.status)
                assertTrue("Failure fixture did not receive the request", server.awaitRequest())
                waitForServiceStopped(context)
                assertTerminalNotificationText(context, R.string.download_failed)
            } finally {
                context.stopService(Intent(context, DownloadService::class.java))
                server.close()
                partial.delete()
                store.remove(id)
                store.close()
                context.deleteDatabase(dbName)
                val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notifications.cancel(NOTIFICATION_ID)
                notifications.cancel(DownloadService.TERMINAL_NOTIFICATION_ID)
            }
        }
    }

    @Test
    fun foregroundJobCancelInterruptsTheActiveTransfer() {
        runBlocking {
            assumeTrue(
                "Service cancel acceptance is opt-in; pass -e runDownloadServiceCancelAcceptance true",
                InstrumentationRegistry.getArguments().getString("runDownloadServiceCancelAcceptance") == "true",
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val video = VideoItem("aqz-KE-bpKQ", "Big Buck Bunny", duration = "9:56", url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
            val dbName = "lowtube-cancel-acceptance-${System.currentTimeMillis()}.db"
            val partial = File(context.cacheDir, "$dbName.part")
            val server = SlowLoopbackServer()
            server.start()
            val store = DownloadJobStore(context, dbName)
            val videoJson = JSONObject().put("id", video.id).put("title", video.title).put("url", video.url).toString()
            val id = store.insert(videoJson, "cancel-loopback-test", false, "cancel-bounded-${video.id}.mp4", "video/mp4", "LowTube acceptance test", partial.absolutePath)
            try {
                startService(context, dbName, testStreamUrl = server.url)
                waitForBytesWritten(store, id, partial)
                val cancelAction = assertForegroundServiceAndNotification(context)
                if (cancelAction != null) cancelAction.send() else cancelService(context, dbName, id)
                val cancelled = withTimeoutPoll {
                    val job = store.get(id) ?: error("Cancelled service job disappeared.")
                    if (job.status == DownloadStatus.CANCELLED || job.status == DownloadStatus.FAILED) job else null
                }
                assertEquals("Active transfer was not cancelled: ${cancelled.reason}", DownloadStatus.CANCELLED, cancelled.status)
                assertTrue(partial.length() in 1 until SlowLoopbackServer.TOTAL_BYTES)
                assertEquals(partial.length(), cancelled.downloadedBytes)
                assertTrue("Client connection remained open after OkHttp cancel", server.awaitClientClosed())
                waitForServiceStopped(context)
                assertTerminalNotificationText(context, R.string.download_cancelled)
                Log.i(TAG, "serviceCancelStatus=${cancelled.status}, bytes=${cancelled.downloadedBytes}")
            } finally {
                context.stopService(Intent(context, DownloadService::class.java))
                server.close()
                partial.delete()
                store.remove(id)
                store.close()
                context.deleteDatabase(dbName)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(DownloadService.TERMINAL_NOTIFICATION_ID)
            }
        }
    }

    private suspend fun waitForTerminalAttempt(store: DownloadJobStore, id: Long) = withTimeoutPoll {
        val job = store.get(id) ?: error("Bounded service job disappeared.")
        if (job.status == DownloadStatus.PAUSED || job.status == DownloadStatus.FAILED || job.status == DownloadStatus.CANCELLED || job.status == DownloadStatus.SUCCESSFUL) job else null
    }

    @Suppress("DEPRECATION")
    private suspend fun waitForServiceStopped(context: Context) {
        withTimeoutPoll {
            val running = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getRunningServices(100).any { it.service.className == DownloadService::class.java.name }
            if (running) null else true
        }
    }

    private suspend fun waitForBytesWritten(store: DownloadJobStore, id: Long, partial: File) = withTimeoutPoll {
        val job = store.get(id) ?: error("Active service job disappeared.")
        if (job.status == DownloadStatus.FAILED || job.status == DownloadStatus.CANCELLED) error("Transfer ended before it could be cancelled: ${job.reason}")
        if (job.status == DownloadStatus.RUNNING && partial.length() > 0) job else null
    }

    private fun processDeathDatabaseName(): String =
        InstrumentationRegistry.getArguments().getString("downloadProcessDeathDatabase")
            ?.takeIf { it.matches(Regex("lowtube-process-death-[a-f0-9]{32}\\.db")) }
            ?: error("A valid downloadProcessDeathDatabase argument is required.")

    private fun processDeathPrefix() = ByteArray(PROCESS_DEATH_PREFIX_BYTES) { (0x41 + it).toByte() }

    @Suppress("DEPRECATION")
    private fun assertForegroundServiceAndNotification(context: Context): PendingIntent? {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val service = activityManager.getRunningServices(100)
            .firstOrNull { it.service.className == DownloadService::class.java.name }
        assertTrue("Active download service was not foreground", service?.foreground == true)

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "notificationPayloadCheck=skipped; POST_NOTIFICATIONS is not granted")
            return null
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val localized = AppLocale.wrap(context)
        val notification = notificationManager.activeNotifications
            .firstOrNull { it.id == NOTIFICATION_ID }
            ?.notification
        assertTrue("Foreground download notification was not posted", notification != null)
        assertEquals(
            localized.getString(R.string.downloads),
            notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = notificationManager.getNotificationChannel("lowtube_downloads")
            assertEquals(localized.getString(R.string.downloads), channel?.name?.toString())
        }
        val cancelAction = notification?.actions.orEmpty().firstOrNull {
            it.title?.toString() == localized.getString(R.string.cancel_download)
        }
        assertTrue("Foreground download notification is missing the Cancel action", cancelAction?.actionIntent != null)
        return cancelAction?.actionIntent
    }

    private fun assertTerminalNotificationText(context: Context, expectedText: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "terminalNotificationCheck=skipped; POST_NOTIFICATIONS is not granted")
            return
        }
        val localized = AppLocale.wrap(context)
        val activeNotifications = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications
        val notification = activeNotifications
            .firstOrNull { it.id == DownloadService.TERMINAL_NOTIFICATION_ID }
            ?.notification
        assertTrue("Terminal download notification was not retained", notification != null)
        assertFalse(
            "The foreground notification remained beside the terminal notification",
            activeNotifications.any { it.id == NOTIFICATION_ID },
        )
        assertEquals(
            localized.getString(expectedText),
            notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertFalse(
            "Terminal download notification still has a Cancel action",
            notification?.actions.orEmpty().any { it.title?.toString() == localized.getString(R.string.cancel_download) },
        )
    }

    private fun startService(context: Context, dbName: String, testStreamUrl: String? = null, testAudioStreamUrl: String? = null) {
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_TEST_DATABASE, dbName)
        testStreamUrl?.let { intent.putExtra(DownloadService.EXTRA_TEST_STREAM_URL, it) }
        testAudioStreamUrl?.let { intent.putExtra(DownloadService.EXTRA_TEST_AUDIO_STREAM_URL, it) }
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
    }

    private fun cancelService(context: Context, dbName: String, id: Long) {
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_CANCEL)
            .putExtra(DownloadService.EXTRA_TEST_DATABASE, dbName)
            .putExtra(DownloadService.EXTRA_ID, id)
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
    }

    private suspend fun <T : Any> withTimeoutPoll(block: () -> T?): T {
        val deadline = System.currentTimeMillis() + SERVICE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            delay(POLL_MS)
        }
        error("Timed out waiting for bounded download service state.")
    }

    private fun rangeHeadersAreSequential(headers: List<String>, totalBytes: Long): Boolean {
        var nextOffset = 0L
        for (header in headers) {
            val range = Regex("(?i)^bytes=(\\d+)-(\\d+)$").matchEntire(header) ?: return false
            val start = range.groupValues[1].toLongOrNull() ?: return false
            val end = range.groupValues[2].toLongOrNull() ?: return false
            if (start != nextOffset || end < start || start >= totalBytes) return false
            nextOffset = minOf(end, totalBytes - 1L) + 1L
        }
        return nextOffset == totalBytes
    }

    private class SlowLoopbackServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val clientClosed = CountDownLatch(1)
        private var thread: Thread? = null
        val url: String get() = "http://127.0.0.1:${server.localPort}/bounded-test.mp4"

        fun start() {
            thread = Thread({
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: $TOTAL_BYTES\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(ByteArray(32 * 1024))
                        output.flush()
                        while (input.read() >= 0) { }
                    }
                } catch (_: Exception) {
                } finally {
                    clientClosed.countDown()
                }
            }, "LowTube-CancelAcceptanceServer").apply { isDaemon = true; start() }
        }

        fun awaitClientClosed(): Boolean = clientClosed.await(5, TimeUnit.SECONDS)

        override fun close() {
            runCatching { server.close() }
            thread?.interrupt()
        }

        companion object { const val TOTAL_BYTES = 1_048_576L }
    }

    private class SlowPartialRangeServer(private val bodyBytes: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val clientClosed = CountDownLatch(1)
        @Volatile var rangeHeader: String? = null
            private set
        private var thread: Thread? = null
        val prefixBytes = 1024
        val url: String get() = "http://127.0.0.1:${server.localPort}/slow-audio-test.m4a"

        fun start() {
            thread = Thread({
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
                        }
                        val range = Regex("(?i)^bytes=(\\d+)-(\\d+)$").matchEntire(rangeHeader.orEmpty())
                            ?: throw IllegalStateException("Audio cancellation request did not contain a bounded Range header.")
                        val start = range.groupValues[1].toInt()
                        val requestedEnd = range.groupValues[2].toInt()
                        require(start >= 0 && start < bodyBytes.size && requestedEnd >= start) { "Audio cancellation Range was outside the fixture." }
                        val end = minOf(requestedEnd, bodyBytes.lastIndex)
                        val responseLength = end - start + 1
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/mp4\r\nContent-Range: bytes $start-$end/${bodyBytes.size}\r\nContent-Length: $responseLength\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bodyBytes, start, minOf(prefixBytes, responseLength))
                        output.flush()
                        while (input.read() >= 0) { }
                    }
                } catch (_: Exception) {
                } finally {
                    clientClosed.countDown()
                }
            }, "LowTube-MuxCancelAcceptanceServer").apply { isDaemon = true; start() }
        }

        fun awaitClientClosed(): Boolean = clientClosed.await(5, TimeUnit.SECONDS)

        override fun close() {
            runCatching { server.close() }
            thread?.interrupt()
        }
    }

    private class RangeResumeLoopbackServer(
        private val resourceBytes: Long = TOTAL_BYTES,
        private val expectedRequests: Int = 1,
        private val bodyBytes: ByteArray? = null,
    ) : AutoCloseable {
        private val server = ServerSocket(0, expectedRequests, InetAddress.getByName("127.0.0.1"))
        private val clientClosed = CountDownLatch(1)
        val rangeHeaders = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var rangeHeader: String? = null
            private set
        private var thread: Thread? = null
        val url: String get() = "http://127.0.0.1:${server.localPort}/recovery-test.mp4"

        fun start() {
            thread = Thread({
                try {
                    for (requestIndex in 0 until expectedRequests) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                            var requestRange = ""
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Range:", ignoreCase = true)) requestRange = line.substringAfter(':').trim()
                            }
                            rangeHeader = requestRange
                            rangeHeaders.add(requestRange)
                            val range = Regex("(?i)^bytes=(\\d+)-(\\d+)$").matchEntire(requestRange)
                                ?: throw IllegalStateException("Resume request did not include a bounded Range header.")
                            val start = range.groupValues[1].toLong()
                            val requestedEnd = range.groupValues[2].toLong()
                            require(start <= requestedEnd && start < resourceBytes) { "Resume request range was outside the test resource." }
                            val end = minOf(requestedEnd, resourceBytes - 1L)
                            val body = bodyBytes?.copyOfRange(start.toInt(), (end + 1L).toInt())
                                ?: ByteArray((end - start + 1L).toInt()) { (start + it).toByte() }
                            val output = socket.getOutputStream()
                            output.write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $start-$end/$resourceBytes\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(body)
                            output.flush()
                            while (input.read() >= 0) { }
                            if (end + 1L == resourceBytes) break
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    clientClosed.countDown()
                }
            }, "LowTube-ResumeAcceptanceServer").apply { isDaemon = true; start() }
        }

        fun awaitClientClosed(): Boolean = clientClosed.await(5, TimeUnit.SECONDS)

        override fun close() {
            runCatching { server.close() }
            thread?.interrupt()
        }

        companion object {
            const val TOTAL_BYTES = 128L
        }
    }

    private companion object {
        const val CHUNK_BYTES = 65_536L
        const val PREFIX_BYTES = 8
        const val RESUME_CHUNK_BYTES = 16L
        const val RATE_LIMIT_CHUNK_BYTES = 4_096L
        const val TRACK_RANGE_CHUNK_BYTES = 8_192L
        const val MAX_TRACK_RANGE_REQUESTS = 16
        const val RATE_LIMIT_MIN_EXPECTED_MS = 3_500L
        const val PROCESS_DEATH_PREFIX_BYTES = 8
        const val PROCESS_DEATH_RESUME_BYTES = 16L
        const val SERVICE_TIMEOUT_MS = 60_000L
        const val POLL_MS = 100L
        const val NOTIFICATION_ID = 4207
        const val TAG = "LowTubeDownloadService"
    }

    private class FailingResponseServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val requestReceived = CountDownLatch(1)
        private var thread: Thread? = null
        val url: String get() = "http://127.0.0.1:${server.localPort}/failure-test.mp4"

        fun start() {
            thread = Thread({
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        output.flush()
                        requestReceived.countDown()
                    }
                } catch (_: Exception) {
                    requestReceived.countDown()
                }
            }, "LowTube-FailedDownloadAcceptanceServer").apply { isDaemon = true; start() }
        }

        fun awaitRequest(): Boolean = requestReceived.await(5, TimeUnit.SECONDS)

        override fun close() {
            runCatching { server.close() }
            thread?.interrupt()
        }
    }
}
