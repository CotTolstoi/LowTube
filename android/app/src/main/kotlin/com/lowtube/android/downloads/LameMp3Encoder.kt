package com.lowtube.android.downloads

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thin JNI binding to the separately shipped LGPL libmp3lame shared library. */
internal class LameMp3Encoder(sampleRate: Int, channels: Int, bitrateKbps: Int) : AutoCloseable {
    private var handle = nativeOpen(sampleRate, channels, bitrateKbps)
    init { check(handle != 0L) { "LAME could not initialize the MP3 encoder." } }

    fun encode(pcm: ShortArray, frames: Int): ByteArray {
        check(handle != 0L)
        return nativeEncode(handle, pcm, frames) ?: error("LAME could not encode an audio frame.")
    }

    fun flush(): ByteArray {
        check(handle != 0L)
        return nativeFlush(handle) ?: error("LAME could not finish the MP3 stream.")
    }

    override fun close() { if (handle != 0L) { nativeClose(handle); handle = 0L } }

    private external fun nativeOpen(sampleRate: Int, channels: Int, bitrateKbps: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int): ByteArray?
    private external fun nativeFlush(handle: Long): ByteArray?
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("mp3lame"); System.loadLibrary("lowtube_lame") }

        /** Encodes interleaved signed 16-bit PCM to a temporary MP3 file and atomically publishes it. */
        fun encodePcmFile(pcmFile: File, output: File, sampleRate: Int, channels: Int, bitrateKbps: Int) {
            require(sampleRate in 8_000..48_000 && channels in 1..2)
            require(bitrateKbps in setOf(96, 128, 192, 256, 320))
            val temporary = File(output.parentFile, output.name + ".encoding")
            temporary.delete()
            try {
                FileOutputStream(temporary).use { sink ->
                    LameMp3Encoder(sampleRate, channels, bitrateKbps).use { encoder ->
                        pcmFile.inputStream().buffered().use { input ->
                            val bytes = ByteArray(1152 * channels * 2)
                            while (true) {
                                val count = input.read(bytes)
                                if (count <= 0) break
                                val aligned = count - count % (channels * 2)
                                if (aligned == 0) continue
                                val samples = ShortArray(aligned / 2)
                                ByteBuffer.wrap(bytes, 0, aligned).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                                sink.write(encoder.encode(samples, samples.size / channels))
                            }
                            sink.write(encoder.flush())
                        }
                    }
                }
                check(temporary.length() > 0L) { "MP3 encoding produced an empty file." }
                if (output.exists() && !output.delete()) error("Android could not replace the MP3 partial file.")
                check(temporary.renameTo(output)) { "Android could not finalize the MP3 file." }
            } finally { temporary.delete() }
        }
    }
}
