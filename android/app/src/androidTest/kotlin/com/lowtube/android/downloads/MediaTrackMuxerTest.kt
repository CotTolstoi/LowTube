package com.lowtube.android.downloads

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class MediaTrackMuxerTest {
    @Test
    fun combinesSeparateAvcAndAacMp4TracksWithoutTranscoding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "mp4-track-mux-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = File(directory, "source.mp4")
        val video = File(directory, "video-only.mp4")
        val audio = File(directory, "audio-only.mp4")
        val output = File(directory, "combined.mp4")
        try {
            instrumentation.context.assets.open("com/lowtube/android/downloads/mux_fixture.mp4").use { input ->
                source.outputStream().use(input::copyTo)
            }
            extractTrack(source, video, "video/avc")
            extractTrack(source, audio, "audio/mp4a-latm")

            MediaTrackMuxer.merge(video, audio, output)

            val result = MediaExtractor()
            try {
                result.setDataSource(output.absolutePath)
                assertEquals(2, result.trackCount)
                val mimeTypes = (0 until result.trackCount)
                    .map { result.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                    .toSet()
                assertEquals(setOf("video/avc", "audio/mp4a-latm"), mimeTypes)
                for (index in 0 until result.trackCount) {
                    val format = result.getTrackFormat(index)
                    assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 0L)
                    result.selectTrack(index)
                }
                var sampleCount = 0
                while (result.sampleTrackIndex >= 0) {
                    sampleCount++
                    if (!result.advance()) break
                }
                assertTrue("The combined MP4 must contain media samples", sampleCount > 0)
            } finally {
                result.release()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun extractTrack(source: File, destination: File, mime: String) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val sourceTrack = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == mime
            }
            val format = extractor.getTrackFormat(sourceTrack)
            extractor.selectTrack(sourceTrack)
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(outputTrack, buffer, info)
                if (!extractor.advance()) break
            }
            muxer.stop()
            started = false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
