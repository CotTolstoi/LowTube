package com.lowtube.android.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lowtube.android.model.VideoItem
import com.lowtube.android.playback.PlaybackDecoderDiagnostics
import com.lowtube.android.playback.configurePlaybackPreload
import com.lowtube.android.playback.createPlaybackLoadControl
import com.lowtube.android.playback.createPlaybackDataSourceFactory
import com.lowtube.android.playback.ExternalAudioMediaSourceFactory
import com.lowtube.android.playback.EXTERNAL_AUDIO_URI
import com.lowtube.android.playback.EXTERNAL_AUDIO_MIME_TYPE
import com.lowtube.android.playback.EXTERNAL_AUDIO_LABEL
import com.lowtube.android.ui.playback.PlaybackResumePolicy
import com.lowtube.android.ui.playback.PlaybackSeekPolicy
import com.lowtube.android.ui.playback.externalSubtitleConfiguration
import com.lowtube.android.ui.playback.replaceExternalSubtitle
import com.lowtube.android.ui.playback.trackOptions
import com.lowtube.android.ui.playback.updateTrackOverride
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Manual network acceptance used only when stream resolver behavior changes. */
@RunWith(AndroidJUnit4::class)
class YoutubeResolverPlaybackAcceptanceTest {
    @Test
    fun resolvedStreamStartsAndAdvancesWithoutReachingTwoMinutes() = runBlocking {
        assumeTrue(
            "Manual playback acceptance is opt-in; pass -e runPlaybackAcceptance true when the player changes",
            InstrumentationRegistry.getArguments().getString("runPlaybackAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val video = VideoItem(
            id = "aqz-KE-bpKQ",
            title = "Big Buck Bunny",
            duration = "9:56",
            url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        )
        PlaybackDecoderDiagnostics.clear(context)
        val cpuStarted = Process.getElapsedCpuTime()
        val wallStarted = SystemClock.elapsedRealtime()
        val pssStarted = Debug.getPss()
        val rssStarted = readRssKiB()
        val stream = InnerTubeSearchRepository(context).resolvePlayback(video)
        withContext(Dispatchers.Main.immediate) {
            val player = ExoPlayer.Builder(context).setLoadControl(createPlaybackLoadControl(context)).build().apply {
                configurePlaybackPreload(this, true)
                addAnalyticsListener(PlaybackDecoderDiagnostics.analyticsListener(context))
            }
            var failure: PlaybackException? = null
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { failure = error }
            })

            try {
                val savedPositionMs = 15_000L
                val startPositionMs = PlaybackResumePolicy.position(false, false) { savedPositionMs }
                val firstItem = MediaItem.Builder()
                    .setMediaId("${video.id}-current")
                    .setUri(stream.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(12_000).build(),
                    )
                    .build()
                val nextItem = MediaItem.Builder()
                    .setMediaId("${video.id}-next")
                    .setUri(stream.uri)
                    .build()
                assertEquals(5_000_000L, player.preloadConfiguration.targetPreloadDurationUs)
                player.setMediaItems(listOf(firstItem, nextItem), 0, startPositionMs)
                player.prepare()
                withTimeout(60_000) {
                    while (player.playbackState != Player.STATE_READY && failure == null) delay(100)
                }
                assertNull(failure)
                assertTrue("Disabled resume must ignore the saved 15 s position", player.currentPosition < 3_000)
                player.play()
                withTimeout(45_000) {
                    while (player.currentMediaItemIndex < 1 && failure == null) {
                        assertTrue("Playback acceptance must stop before 2 minutes", player.currentPosition < 120_000)
                        delay(100)
                    }
                }
                assertEquals("The preloaded queue item did not become current", 1, player.currentMediaItemIndex)
                withTimeout(15_000) {
                    while (player.currentPosition < 1_000 && failure == null) {
                        assertTrue("Playback acceptance must stop before 2 minutes", player.currentPosition < 120_000)
                        delay(100)
                    }
                }
                player.pause()
                assertNull(failure)
                assertTrue("Expected media time to advance to the next item", player.currentPosition >= 1_000)
                assertTrue("Playback must stop within the first 2 minutes", player.currentPosition < 120_000)
                val decoderSnapshot = PlaybackDecoderDiagnostics.read(
                    context.getSharedPreferences(PlaybackDecoderDiagnostics.PREFERENCES_NAME, Context.MODE_PRIVATE),
                )
                assertTrue("Media3 did not report the initialized video decoder", decoderSnapshot.name.isNotBlank())
                assertTrue("Media3 did not report the input codec/format", decoderSnapshot.inputFormat.isNotBlank())
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                    assertEquals(PlaybackDecoderDiagnostics.HARDWARE_STATUS_UNKNOWN, decoderSnapshot.hardwareStatus)
                }
                System.out.println(
                    "preloadAcceptance=enabled; targetPreloadUs=${player.preloadConfiguration.targetPreloadDurationUs}; " +
                        "currentMediaItemIndex=${player.currentMediaItemIndex}; resumePlaybackAcceptance=disabled; " +
                        "actualVideoDecoder=${decoderSnapshot.name}; inputFormat=${decoderSnapshot.inputFormat}; " +
                        "hardwareAccelerationStatus=${decoderSnapshot.hardwareStatus}; " +
                        "savedPositionMs=$savedPositionMs; startPositionMs=$startPositionMs; " +
                        "mediaMs=${player.currentPosition}; cpuMs=${Process.getElapsedCpuTime() - cpuStarted}; " +
                        "wallMs=${SystemClock.elapsedRealtime() - wallStarted}; pssKiB=$pssStarted->${Debug.getPss()}; " +
                        "rssKiB=$rssStarted->${readRssKiB()}",
                )
            } finally {
                player.pause()
                player.release()
                PlaybackDecoderDiagnostics.clear(context)
            }
        }
    }

    @Test
    fun externalWebVttSubtitleAttachesWhilePreservingPlaybackPosition() = runBlocking {
        assumeTrue(
            "Pass -e runExternalSubtitlePlaybackAcceptance true after external subtitle player changes",
            InstrumentationRegistry.getArguments().getString("runExternalSubtitlePlaybackAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val video = VideoItem(
            id = "aqz-KE-bpKQ",
            title = "Big Buck Bunny",
            duration = "9:56",
            url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        )
        val stream = InnerTubeSearchRepository(context).resolvePlayback(video)
        val subtitleFile = File(context.cacheDir, "lowtube-external-${System.currentTimeMillis()}.vtt")
        subtitleFile.writeText(
            "WEBVTT\n\n00:00:00.000 --> 00:00:30.000\nLowTube external subtitle acceptance\n",
            Charsets.UTF_8,
        )
        val subtitleUri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            subtitleFile,
        )
        val subtitle = externalSubtitleConfiguration(context, subtitleUri)
            ?: throw AssertionError("The test WebVTT fixture was not recognized")
        var failure: PlaybackException? = null
        val player = withContext(Dispatchers.Main.immediate) {
            ExoPlayer.Builder(context).setLoadControl(createPlaybackLoadControl(context)).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failure = error }
                })
                setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(video.id)
                        .setUri(stream.uri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder().setEndPositionMs(30_000).build(),
                        )
                        .build(),
                )
                prepare()
            }
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                withTimeout(45_000) {
                    while (player.playbackState != Player.STATE_READY && failure == null) delay(100)
                }
                assertNull(failure)
                player.play()
                withTimeout(20_000) {
                    while (player.currentPosition < 2_000 && failure == null) delay(50)
                }
                assertNull(failure)
                val beforeAttach = player.currentPosition
                assertTrue("The test player exceeded the two-minute limit", beforeAttach < 120_000)
                assertTrue("External subtitle item could not be applied", replaceExternalSubtitle(player, subtitle))
                withTimeout(45_000) {
                    while (player.playbackState != Player.STATE_READY && failure == null) delay(100)
                }
                assertNull(failure)
                assertTrue("External subtitles were not exposed as a text track", player.currentTracks.groups.any {
                    it.type == C.TRACK_TYPE_TEXT && (0 until it.length).any(it::isTrackSupported)
                })
                val subtitleOptions = trackOptions(player.currentTracks, C.TRACK_TYPE_TEXT, player, "Subtitle")
                assertTrue("The playback controls did not expose a supported subtitle choice", subtitleOptions.isNotEmpty())
                updateTrackOverride(player, C.TRACK_TYPE_TEXT, null, disable = true)
                withTimeout(10_000) {
                    while (player.currentTracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSelected)
                        }
                    ) delay(50)
                }
                assertTrue("Subtitle Off left a text track selected", player.currentTracks.groups.none { group ->
                    group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSelected)
                })
                updateTrackOverride(player, C.TRACK_TYPE_TEXT, subtitleOptions.first())
                withTimeout(10_000) {
                    while (player.currentTracks.groups.none { group ->
                            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSelected)
                        }
                    ) delay(50)
                }
                assertTrue("The selected subtitle track was not enabled", player.currentTracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSelected)
                })
                updateTrackOverride(player, C.TRACK_TYPE_TEXT, null, disable = true)
                withTimeout(10_000) {
                    while (player.currentTracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSelected)
                        }
                    ) delay(50)
                }
                assertTrue("Subtitle Off left a text track selected", player.currentTracks.groups.none { group ->
                    group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSelected)
                })
                updateTrackOverride(player, C.TRACK_TYPE_TEXT, null)
                withTimeout(10_000) {
                    while (player.currentTracks.groups.none { group ->
                            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSelected)
                        }
                    ) delay(50)
                }
                assertTrue("Subtitle Auto did not restore an available text track", player.currentTracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSelected)
                })
                assertTrue("Adding subtitles lost the current media position", player.currentPosition >= beforeAttach - 1_000)
                withTimeout(10_000) {
                    while (player.currentPosition < beforeAttach + 1_000 && failure == null) {
                        assertTrue("Subtitle acceptance exceeded two minutes", player.currentPosition < 120_000)
                        delay(50)
                    }
                }
                player.pause()
                assertNull(failure)
                assertTrue("The player did not resume after attaching subtitles", player.currentPosition >= beforeAttach + 1_000)
            }
        } finally {
            withContext(Dispatchers.Main.immediate) { player.pause(); player.release() }
            subtitleFile.delete()
        }
    }

    @Test
    fun externalAudioTrackMergesWithVideoAndAdvancesWithinTwoMinutes() = runBlocking {
        assumeTrue(
            "Pass -e runExternalAudioPlaybackAcceptance true after external audio player changes",
            InstrumentationRegistry.getArguments().getString("runExternalAudioPlaybackAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val video = VideoItem(
            id = "aqz-KE-bpKQ",
            title = "Big Buck Bunny",
            duration = "9:56",
            url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        )
        val stream = InnerTubeSearchRepository(context).resolvePlayback(video)
        val cpuStarted = Process.getElapsedCpuTime()
        val wallStarted = SystemClock.elapsedRealtime()
        val pssStarted = Debug.getPss()
        val rssStarted = readRssKiB()
        val audioFile = File(context.cacheDir, "lowtube-external-${System.currentTimeMillis()}.wav")
        writePcmWave(audioFile, durationSeconds = 30)
        val audioUri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            audioFile,
        )
        val metadataExtras = Bundle().apply {
            putString(EXTERNAL_AUDIO_URI, audioUri.toString())
            putString(EXTERNAL_AUDIO_MIME_TYPE, "audio/wav")
            putString(EXTERNAL_AUDIO_LABEL, audioFile.name)
        }
        var failure: PlaybackException? = null
        val player = withContext(Dispatchers.Main.immediate) {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    ExternalAudioMediaSourceFactory(
                        DefaultMediaSourceFactory(createPlaybackDataSourceFactory(context)),
                    ),
                )
                .setLoadControl(createPlaybackLoadControl(context))
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { failure = error }
                    })
                    setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(video.id)
                            .setUri(stream.uri)
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder().setExtras(metadataExtras).build(),
                            )
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(30_000).build(),
                            )
                            .build(),
                    )
                    prepare()
                }
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                withTimeout(60_000) {
                    while (player.playbackState != Player.STATE_READY && failure == null) delay(100)
                }
                assertNull(failure)
                assertTrue("Merged source did not expose an audio track", player.currentTracks.groups.any {
                    it.type == C.TRACK_TYPE_AUDIO && (0 until it.length).any(it::isTrackSupported)
                })
                player.play()
                withTimeout(20_000) {
                    while (player.currentPosition < 2_000 && failure == null) {
                        assertTrue("External audio test exceeded two minutes", player.currentPosition < 120_000)
                        delay(50)
                    }
                }
                player.pause()
                assertNull(failure)
                assertTrue("Video media time did not advance", player.currentPosition >= 2_000)
                assertTrue("External audio test exceeded two minutes", player.currentPosition < 120_000)
                println(
                    "externalAudioAcceptance=merged; mediaMs=${player.currentPosition}; " +
                        "cpuMs=${Process.getElapsedCpuTime() - cpuStarted}; " +
                        "wallMs=${SystemClock.elapsedRealtime() - wallStarted}; " +
                        "pssKiB=$pssStarted->${Debug.getPss()}; rssKiB=$rssStarted->${readRssKiB()}",
                )
            }
        } finally {
            withContext(Dispatchers.Main.immediate) { player.pause(); player.release() }
            audioFile.delete()
        }
    }

    @Test
    fun tenSecondSeekTargetsMoveMediaInsideThirtySecondClip() = runBlocking {
        assumeTrue(
            "Pass -e runSeekGesturePlaybackAcceptance true after playback seek changes",
            InstrumentationRegistry.getArguments().getString("runSeekGesturePlaybackAcceptance") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val video = VideoItem(
            id = "aqz-KE-bpKQ",
            title = "Big Buck Bunny",
            duration = "9:56",
            url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        )
        val stream = InnerTubeSearchRepository(context).resolvePlayback(video)
        var failure: PlaybackException? = null
        val player = withContext(Dispatchers.Main.immediate) {
            ExoPlayer.Builder(context).setLoadControl(createPlaybackLoadControl(context)).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failure = error }
                })
                setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(video.id)
                        .setUri(stream.uri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder().setEndPositionMs(30_000).build(),
                        )
                        .build(),
                )
                prepare()
            }
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                withTimeout(60_000) {
                    while (player.playbackState != Player.STATE_READY && failure == null) delay(100)
                }
                assertNull(failure)
                assertTrue("The seek fixture must remain clipped to 30 seconds", player.duration in 1L..30_000L)
                player.play()
                withTimeout(20_000) {
                    while (player.currentPosition < 1_000 && failure == null) {
                        assertTrue("Seek acceptance exceeded two minutes", player.currentPosition < 120_000)
                        delay(50)
                    }
                }
                player.pause()
                assertNull(failure)

                val forwardTarget = PlaybackSeekPolicy.target(player.currentPosition, player.duration, 10_000L)
                    ?: throw AssertionError("The forward seek target was rejected")
                player.seekTo(forwardTarget)
                withTimeout(10_000) {
                    while (player.currentPosition < forwardTarget - 1_000L && failure == null) delay(50)
                }
                assertNull(failure)
                assertTrue("Forward seek did not move the media position", player.currentPosition >= forwardTarget - 1_000L)

                val backwardTarget = PlaybackSeekPolicy.target(player.currentPosition, player.duration, -10_000L)
                    ?: throw AssertionError("The backward seek target was rejected")
                player.seekTo(backwardTarget)
                withTimeout(10_000) {
                    while (player.currentPosition > backwardTarget + 1_000L && failure == null) delay(50)
                }
                player.pause()
                assertNull(failure)
                assertTrue("Backward seek did not move the media position", player.currentPosition <= backwardTarget + 1_000L)
                assertTrue("Seek acceptance exceeded two minutes", player.currentPosition < 120_000)
                println(
                    "seekGesturePlaybackAcceptance=ten_seconds; durationMs=${player.duration}; " +
                        "forwardTargetMs=$forwardTarget; backwardTargetMs=$backwardTarget; mediaMs=${player.currentPosition}",
                )
            }
        } finally {
            withContext(Dispatchers.Main.immediate) { player.pause(); player.release() }
        }
    }

    private fun writePcmWave(file: File, durationSeconds: Int) {
        val sampleRate = 8_000
        val channels = 1
        val bitsPerSample = 16
        val dataSize = sampleRate * channels * (bitsPerSample / 8) * durationSeconds
        java.io.DataOutputStream(file.outputStream().buffered()).use { output ->
            fun writeLittleEndian(value: Int) {
                output.writeByte(value and 0xff)
                output.writeByte((value shr 8) and 0xff)
                output.writeByte((value shr 16) and 0xff)
                output.writeByte((value shr 24) and 0xff)
            }
            output.writeBytes("RIFF")
            writeLittleEndian(36 + dataSize)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            writeLittleEndian(16)
            output.writeByte(1); output.writeByte(0)
            output.writeByte(channels); output.writeByte(0)
            writeLittleEndian(sampleRate)
            writeLittleEndian(sampleRate * channels * bitsPerSample / 8)
            output.writeByte(channels * bitsPerSample / 8); output.writeByte(0)
            output.writeByte(bitsPerSample); output.writeByte(0)
            output.writeBytes("data")
            writeLittleEndian(dataSize)
            repeat(dataSize / 2) { output.writeByte(0); output.writeByte(0) }
        }
    }

    private fun readRssKiB(): Long? = runCatching {
        java.io.File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
        }
    }.getOrNull()
}
