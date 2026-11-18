package com.lowtube.android.playback

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.sin

/** Runs only after the local Media3 source path changes; the synthetic audio is stopped after <2 s. */
@RunWith(AndroidJUnit4::class)
class LocalAudioPlaybackAcceptanceTest {
    @Test
    fun playsLocalContentUriAndAdvancesBriefly() = runBlocking {
        assumeTrue(
            "Pass -e runLocalAudioPlaybackAcceptance true after a player-source change",
            InstrumentationRegistry.getArguments().getString("runLocalAudioPlaybackAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioFile = File(context.cacheDir, "lowtube-local-audio-${System.currentTimeMillis()}.wav")
        writeWaveFile(audioFile, durationSeconds = 6)
        val contentUri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", audioFile)
        var failure: PlaybackException? = null
        val player = withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(createPlaybackDataSourceFactory(context)))
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { failure = error }
                    })
                }
        }
        val cpuStart = Process.getElapsedCpuTime()
        val wallStart = SystemClock.elapsedRealtime()
        val pssStart = Debug.getPss()
        val rssStart = readRssKiB()
        try {
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                player.setMediaItem(MediaItem.Builder().setUri(contentUri).setMimeType("audio/wav").build())
                player.prepare()
            }
            var mediaPosition = 0L
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                withTimeout(15_000) {
                    while (player.playbackState != Player.STATE_READY && failure == null) delay(50)
                }
                assertNull(failure)
                player.play()
                withTimeout(10_000) {
                    while (player.currentPosition < 500 && failure == null) {
                        assertTrue("Local audio acceptance must stop before two minutes", player.currentPosition < 120_000)
                        delay(50)
                    }
                }
                player.pause()
                mediaPosition = player.currentPosition
                assertNull(failure)
                assertTrue("Local audio media time did not advance", mediaPosition >= 500)
                assertTrue("Local audio acceptance exceeded two minutes", mediaPosition < 120_000)
            }
            System.out.println(
                "localAudioPlaybackAcceptance=contentUri=true; mediaMs=$mediaPosition; " +
                    "cpuMs=${Process.getElapsedCpuTime() - cpuStart}; wallMs=${SystemClock.elapsedRealtime() - wallStart}; " +
                    "pssKiB=$pssStart->${Debug.getPss()}; rssKiB=$rssStart->${readRssKiB()}",
            )
        } finally {
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { player.release() }
            audioFile.delete()
        }
    }

    private fun writeWaveFile(file: File, durationSeconds: Int) {
        val sampleRate = 8_000
        val channels = 1
        val bytesPerSample = 2
        val sampleCount = sampleRate * durationSeconds
        val dataBytes = sampleCount * channels * bytesPerSample
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLittleEndian(output, 36 + dataBytes)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            writeIntLittleEndian(output, 16)
            writeShortLittleEndian(output, 1)
            writeShortLittleEndian(output, channels)
            writeIntLittleEndian(output, sampleRate)
            writeIntLittleEndian(output, sampleRate * channels * bytesPerSample)
            writeShortLittleEndian(output, channels * bytesPerSample)
            writeShortLittleEndian(output, bytesPerSample * 8)
            output.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLittleEndian(output, dataBytes)
            repeat(sampleCount) { frame ->
                val sample = (sin(2.0 * PI * 440.0 * frame / sampleRate) * 4_000.0).toInt()
                writeShortLittleEndian(output, sample)
            }
        }
    }

    private fun writeShortLittleEndian(output: OutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeIntLittleEndian(output: OutputStream, value: Int) {
        repeat(4) { shift -> output.write((value ushr (shift * 8)) and 0xff) }
    }

    private fun readRssKiB(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
        }
    }.getOrNull()
}
