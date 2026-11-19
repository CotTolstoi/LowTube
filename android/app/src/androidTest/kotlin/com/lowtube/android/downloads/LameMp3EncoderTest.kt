package com.lowtube.android.downloads

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LameMp3EncoderTest {
    @Test fun encodesBoundedSyntheticPcmToMp3() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val pcm = File(dir, "lowtube-lame-smoke.pcm")
        val mp3 = File(dir, "lowtube-lame-smoke.mp3")
        try {
            pcm.outputStream().buffered().use { output ->
                val bytes = ByteBuffer.allocate(1152 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (frame in 0 until 48_000) {
                    val sample = (sin(2.0 * PI * 440.0 * frame / 48_000.0) * 12_000).toInt().toShort()
                    bytes.putShort(sample).putShort(sample)
                    if (!bytes.hasRemaining()) { output.write(bytes.array()); bytes.clear() }
                }
                if (bytes.position() > 0) output.write(bytes.array(), 0, bytes.position())
            }
            LameMp3Encoder.encodePcmFile(pcm, mp3, sampleRate = 48_000, channels = 2, bitrateKbps = 192)
            val header = mp3.inputStream().use { byteArrayOf(it.read().toByte(), it.read().toByte()) }
            assertTrue("Encoded MP3 should contain data", mp3.length() > 4_000L)
            assertTrue("Encoded stream should begin with an MPEG audio frame", header.size == 2 && header[0].toInt() and 0xff == 0xff && header[1].toInt() and 0xe0 == 0xe0)
        } finally { pcm.delete(); mp3.delete(); File(mp3.parentFile, mp3.name + ".encoding").delete() }
    }

    @Test fun decodesAacFixtureAndEncodesMp3OnDevice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = instrumentation.targetContext.cacheDir
        val source = File(dir, "lowtube-lame-aac-source.mp4")
        val mp3 = File(dir, "lowtube-lame-aac-output.mp3")
        try {
            instrumentation.context.assets.open("com/lowtube/android/downloads/mux_audio_track.mp4").use { input ->
                FileOutputStream(source).use { output -> input.copyTo(output) }
            }
            AudioMp3Transcoder.transcode(source, mp3, 192) { false }
            assertTrue("Decoded and encoded MP3 should contain data", mp3.length() > 1_000L)
        } finally { source.delete(); mp3.delete(); File(mp3.parentFile, mp3.name + ".encoding").delete() }
    }
}
