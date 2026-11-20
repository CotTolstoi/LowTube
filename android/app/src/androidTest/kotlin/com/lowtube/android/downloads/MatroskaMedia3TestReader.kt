package com.lowtube.android.downloads

import androidx.media3.common.C
import androidx.media3.common.DataReader
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
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Uses the same Matroska parser as LowTube, including on API 23 where platform MediaExtractor lacks MKV support. */
internal object MatroskaMedia3TestReader {
    fun assertTracksAndSamples(file: File, expectedMimeTypes: Set<String>) {
        val reader = RandomAccessFileReader(file)
        val recorder = RecordingExtractorOutput()
        val extractor = MatroskaExtractor()
        try {
            var input = DefaultExtractorInput(reader, 0L, file.length())
            assertTrue("Media3 must recognize the Matroska EBML header", extractor.sniff(input))
            reader.seek(0L)
            input = DefaultExtractorInput(reader, 0L, file.length())
            extractor.init(recorder)
            val seek = PositionHolder()
            var result: Int
            var iterations = 0L
            do {
                result = extractor.read(input, seek)
                if (result == Extractor.RESULT_SEEK) {
                    reader.seek(seek.position)
                    input = DefaultExtractorInput(reader, seek.position, file.length())
                    extractor.seek(seek.position, 0L)
                }
                iterations++
                assertTrue("Media3 parser must finish within a file-sized iteration bound", iterations <= file.length() + 10L)
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
        private val input = RandomAccessFile(file, "r")
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
}
