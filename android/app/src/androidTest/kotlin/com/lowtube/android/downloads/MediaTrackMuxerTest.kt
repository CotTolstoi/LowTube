package com.lowtube.android.downloads

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.DataReader
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class MediaTrackMuxerTest {
    @Test
    fun remuxesAvcAacMp4IntoMatroskaWithoutTranscoding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "avc-aac-matroska-test").apply { deleteRecursively(); mkdirs() }
        val source = File(directory, "source.mp4")
        val output = File(directory, "combined.mkv")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("com/lowtube/android/downloads/mux_fixture.mp4").use { input ->
                source.outputStream().use(input::copyTo)
            }
            MatroskaMuxer.muxSingle(source, output)
            assertMatroskaWithMedia3(output, setOf("video/avc", "audio/mp4a-latm"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun remuxesVp8VorbisWebmIntoMatroskaWithoutTranscoding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "vp8-vorbis-matroska-test").apply { deleteRecursively(); mkdirs() }
        val source = File(directory, "source.webm")
        val output = File(directory, "combined.mkv")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("com/lowtube/android/downloads/webm_vp8_vorbis.webm").use { input ->
                source.outputStream().use(input::copyTo)
            }
            MatroskaMuxer.muxSingle(source, output)
            assertMatroskaWithMedia3(output, setOf("video/x-vnd.on2.vp8", "audio/vorbis"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun remuxesVp9OpusWebmIntoMatroskaWithoutTranscoding() {
        assumeTrue("VP9/Opus fixture requires Android 10+ MediaExtractor support", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "vp9-opus-matroska-test").apply { deleteRecursively(); mkdirs() }
        val source = File(directory, "source.webm")
        val output = File(directory, "combined.mkv")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("com/lowtube/android/downloads/webm_vp9_opus.webm").use { input ->
                source.outputStream().use(input::copyTo)
            }
            MatroskaMuxer.muxSingle(source, output)
            assertMatroskaWithMedia3(output, setOf("video/x-vnd.on2.vp9", "audio/opus"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun remuxesSeparateAvcAacTracksIntoMatroskaWithoutTranscoding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "separate-avc-aac-matroska-test").apply { deleteRecursively(); mkdirs() }
        val source = File(directory, "source.mp4")
        val output = File(directory, "combined.mkv")
        try {
            instrumentation.context.assets.open("com/lowtube/android/downloads/mux_fixture.mp4").use { input ->
                source.outputStream().use(input::copyTo)
            }
            MatroskaMuxer.muxSeparate(source, source, output)
            assertMatroskaWithMedia3(output, setOf("video/avc", "audio/mp4a-latm"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun remuxesSeparateVp8VorbisTracksIntoMatroskaWithoutTranscoding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "separate-vp8-vorbis-matroska-test").apply { deleteRecursively(); mkdirs() }
        val source = File(directory, "source.webm")
        val output = File(directory, "combined.mkv")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(
                "com/lowtube/android/downloads/webm_vp8_vorbis.webm",
            ).use { input -> source.outputStream().use(input::copyTo) }
            MatroskaMuxer.muxSeparate(source, source, output)
            assertMatroskaWithMedia3(output, setOf("video/x-vnd.on2.vp8", "audio/vorbis"))
        } finally {
            directory.deleteRecursively()
        }
    }

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

    @Test
    fun combinesSeparateVp8AndVorbisWebmTracksWithoutTranscoding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "vp8-vorbis-webm-mux-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = File(directory, "source.webm")
        val output = File(directory, "combined.webm")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(
                "com/lowtube/android/downloads/webm_vp8_vorbis.webm",
            ).use { input -> source.outputStream().use(input::copyTo) }
            MediaTrackMuxer.merge(source, source, output)

            assertCombined(output, setOf("video/x-vnd.on2.vp8", "audio/vorbis"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun combinesSeparateVp9AndOpusWebmTracksWithoutTranscoding() {
        assumeTrue("Platform WebM VP9/Opus muxing requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "vp9-opus-webm-mux-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = File(directory, "source.webm")
        val output = File(directory, "combined.webm")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(
                "com/lowtube/android/downloads/webm_vp9_opus.webm",
            ).use { input -> source.outputStream().use(input::copyTo) }
            MediaTrackMuxer.merge(source, source, output)

            assertCombined(output, setOf("video/x-vnd.on2.vp9", "audio/opus"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertCombined(output: File, expectedMimeTypes: Set<String>) {
        val result = MediaExtractor()
        try {
            result.setDataSource(output.absolutePath)
            assertEquals(2, result.trackCount)
            val mimeTypes = (0 until result.trackCount)
                .map { result.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .toSet()
            assertEquals(expectedMimeTypes, mimeTypes)
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
            assertTrue("The combined file must contain media samples", sampleCount > 0)
        } finally {
            result.release()
        }
    }

    private fun assertMatroskaWithMedia3(output: File, expectedMimeTypes: Set<String>) {
        val reader = RandomAccessFileReader(output)
        val recorder = RecordingExtractorOutput()
        val extractor = MatroskaExtractor()
        try {
            var input = DefaultExtractorInput(reader, 0L, output.length())
            assertTrue("Media3 must recognize the Matroska EBML header", extractor.sniff(input))
            reader.seek(0L)
            input = DefaultExtractorInput(reader, 0L, output.length())
            extractor.init(recorder)
            val seek = PositionHolder()
            var result: Int
            var iterations = 0
            do {
                result = extractor.read(input, seek)
                if (result == Extractor.RESULT_SEEK) {
                    reader.seek(seek.position)
                    input = DefaultExtractorInput(reader, seek.position, output.length())
                    extractor.seek(seek.position, 0L)
                }
                iterations++
                assertTrue("Media3 parser must finish within a file-sized iteration bound", iterations <= output.length().toInt() + 10)
            } while (result != Extractor.RESULT_END_OF_INPUT)
            assertEquals(expectedMimeTypes, recorder.mimeTypes.values.toSet())
            assertEquals(expectedMimeTypes.size, recorder.samplesByTrack.size)
            assertTrue("Media3 must receive media samples from every Matroska track", recorder.samplesByTrack.values.all { it > 0 })
        } finally {
            extractor.release()
            reader.close()
        }
    }

    private class RandomAccessFileReader(file: File) : DataReader, java.io.Closeable {
        private val input = java.io.RandomAccessFile(file, "r")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            return runCatching { input.read(buffer, offset, length) }.getOrElse { throw IOException(it) }
        }
        fun seek(position: Long) = input.seek(position)
        override fun close() = input.close()
    }

    private class RecordingExtractorOutput : ExtractorOutput {
        val mimeTypes = mutableMapOf<Int, String>()
        val samplesByTrack = mutableMapOf<Int, Int>()
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) {
                mimeTypes[id] = format.sampleMimeType.orEmpty()
            }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val buffer = ByteArray(8192)
                var remaining = length
                var total = 0
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) {
                        if (total == 0 && allowEndOfInput) return C.RESULT_END_OF_INPUT
                        throw IOException("Unexpected end of Matroska sample data: track=$id request=$length read=$total.")
                    }
                    total += count
                    remaining -= count
                }
                return total
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                data.skipBytes(length)
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                samplesByTrack[id] = (samplesByTrack[id] ?: 0) + 1
            }
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private fun extractTrack(
        source: File,
        destination: File,
        mime: String,
        outputFormat: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    ) {
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
            muxer = MediaMuxer(destination.absolutePath, outputFormat)
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
