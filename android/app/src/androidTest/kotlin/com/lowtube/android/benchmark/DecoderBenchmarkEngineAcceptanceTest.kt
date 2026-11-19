package com.lowtube.android.benchmark

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lowtube.android.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** Manual, network-free acceptance for the Android decoder comparison feature. */
@RunWith(AndroidJUnit4::class)
class DecoderBenchmarkEngineAcceptanceTest {
    @Test
    fun decoderProfilesRunAgainstTheSameBoundedLocalVideo() = runBlocking {
        assumeTrue(
            "Pass -e runDecoderBenchmarkAcceptance true to run this bounded local benchmark acceptance",
            InstrumentationRegistry.getArguments().getString("runDecoderBenchmarkAcceptance") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "decoder-benchmark-acceptance").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = File(directory, "synthetic-avc-16s.mp4")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            createRepeatedAvcFixture(instrumentation.context, source, targetDurationUs = 16_000_000L)
            val durationExtractor = MediaExtractor()
            val durationUs = try {
                durationExtractor.setDataSource(source.absolutePath)
                val track = (0 until durationExtractor.trackCount).first { index ->
                    durationExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == "video/avc"
                }
                durationExtractor.getTrackFormat(track).getLong(MediaFormat.KEY_DURATION)
            } finally {
                durationExtractor.release()
            }
            assertTrue("The synthetic fixture must exceed the benchmark clip", durationUs >= 12_000_000L)

            val profiles = buildList {
                add(AndroidDecoderProfile.DEFAULT)
                add(AndroidDecoderProfile.SOFTWARE_PREFERRED)
                if (DecoderBenchmarkEngine.isHardwareProfileAvailable(Build.VERSION.SDK_INT) &&
                    DecoderBenchmarkEngine.hasHardwareVideoDecoder(context, android.net.Uri.fromFile(source))
                ) {
                    add(AndroidDecoderProfile.HARDWARE_ONLY)
                }
            }
            assertTrue("At least the default and software profiles should be exercised", profiles.size >= 2)

            scenario = ActivityScenario.launch(MainActivity::class.java)
            for (profile in profiles) {
                val player = DecoderBenchmarkEngine.createPlayer(context, profile)
                var playerView: PlayerView? = null
                try {
                    scenario.onActivity { activity ->
                        val view = PlayerView(activity)
                        view.player = player
                        activity.setContentView(view)
                        playerView = view
                    }
                    val result = withContext(Dispatchers.Main.immediate) {
                        DecoderBenchmarkEngine.run(
                            context = context,
                            player = player,
                            uri = android.net.Uri.fromFile(source),
                            profile = profile,
                            requestedSeconds = 10,
                        ) { _, _ -> }
                    }
                    assertEquals(profile, result.profile)
                    assertTrue("${profile.name} must report the initialized decoder", result.decoderName.isNotBlank())
                    assertTrue("${profile.name} must report the selected video format", result.inputFormat.isNotBlank())
                    assertTrue("${profile.name} must advance the same fixture", result.mediaAdvanceSeconds >= 6.0)
                    assertTrue("${profile.name} exceeded the 2-minute media cap", result.mediaAdvanceSeconds <= 10.0)
                    assertTrue("${profile.name} exceeded the bounded wall time", result.wallSeconds < 120.0)
                    println("decoderBenchmarkAcceptance; ${result.asReportLine()}")
                } finally {
                    withContext(Dispatchers.Main.immediate) {
                        playerView?.player = null
                        player.release()
                    }
                }
            }
        } finally {
            scenario?.close()
            directory.deleteRecursively()
        }
    }

    private fun createRepeatedAvcFixture(context: android.content.Context, destination: File, targetDurationUs: Long) {
        val source = File(destination.parentFile, "source-video-track.mp4")
        context.assets.open("com/lowtube/android/downloads/mux_video_track.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            val sourceTrack = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == "video/avc"
            }
            val format = extractor.getTrackFormat(sourceTrack)
            val sourceDurationUs = format.getLong(MediaFormat.KEY_DURATION)
            require(sourceDurationUs > 0L) { "The AVC fixture has no duration." }
            extractor.selectTrack(sourceTrack)
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var offsetUs = 0L
            while (offsetUs < targetDurationUs) {
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val presentationTimeUs = offsetUs + extractor.sampleTime
                    if (presentationTimeUs >= targetDurationUs) break
                    info.set(0, size, presentationTimeUs, extractor.sampleFlags)
                    muxer.writeSampleData(outputTrack, buffer, info)
                    if (!extractor.advance()) break
                }
                offsetUs += sourceDurationUs
            }
            muxer.stop()
            muxerStarted = false
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
            source.delete()
        }
    }
}
