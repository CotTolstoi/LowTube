package com.lowtube.android.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lowtube.android.MainActivity
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.PlaybackSession
import com.lowtube.android.data.PlaybackSessionStore
import com.lowtube.android.data.PlaybackBufferPreferences
import com.lowtube.android.data.PlaybackStreamPreferences
import com.lowtube.android.model.ResolvedVideoStream
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns Android media playback independently of the Activity lifecycle. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var settings: SharedPreferences? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val dataSourceFactory = createPlaybackDataSourceFactory(this)
        val preferences = getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
        settings = preferences
        val preloadEnabled = preferences.getBoolean(PREF_PRELOAD_NEXT, true) &&
            preferences.getBoolean(PREF_AUTO_NEXT, true)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ExternalAudioMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory)))
            .setLoadControl(createPlaybackLoadControl(this))
            .build().apply {
            configurePlaybackPreload(this, preloadEnabled)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
        player.addAnalyticsListener(PlaybackDecoderDiagnostics.analyticsListener(this))
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                PlaybackDecoderDiagnostics.recordFailure(this@PlaybackService, error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) removeFutureItems(player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                PlaybackDecoderDiagnostics.clear(this@PlaybackService)
            }
        })
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == PREF_PRELOAD_NEXT || key == PREF_AUTO_NEXT) {
                val enabled = changed.getBoolean(PREF_PRELOAD_NEXT, true) &&
                    changed.getBoolean(PREF_AUTO_NEXT, true)
                configurePlaybackPreload(player, enabled)
                if (!enabled) removeFutureItems(player)
            }
        }
        settingsListener = preferenceListener
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                    playbackResumption(isForPlayback)
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        settingsListener?.let { listener -> settings?.unregisterOnSharedPreferenceChangeListener(listener) }
        settingsListener = null
        settings = null
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun playbackResumption(
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val checkpoint = PlaybackSessionStore(this).load()
            ?: return Futures.immediateFailedFuture(IllegalStateException("No saved media item is available to resume."))
        if (!isForPlayback) {
            val metadataOnlyItem = resumptionMediaItem(checkpoint, stream = null, includeLocalUri = false, includeArtwork = false)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(listOf(metadataOnlyItem), 0, C.TIME_UNSET),
            )
        }

        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val job = serviceScope.launch {
            try {
                val stream = if (checkpoint.localAudioTrack == null && checkpoint.localVideoSource == null) {
                    InnerTubeSearchRepository(applicationContext).resolvePlayback(
                        checkpoint.video,
                        streamPreferences(),
                    )
                } else null
                val item = resumptionMediaItem(
                    checkpoint,
                    stream,
                    includeLocalUri = true,
                    includeArtwork = true,
                )
                val position = resumePositionMs(checkpoint)
                val savedSpeed = getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
                    .getFloat("playback_speed", 1f)
                    .coerceIn(0.25f, 2f)
                withContext(Dispatchers.Main.immediate) {
                    mediaSession?.player?.setPlaybackParameters(PlaybackParameters(savedSpeed))
                }
                future.set(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, position))
            } catch (cancelled: CancellationException) {
                future.cancel(false)
                throw cancelled
            } catch (failure: Exception) {
                future.setException(failure)
            }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        job.invokeOnCompletion { failure ->
            if (failure is CancellationException && !future.isDone) future.cancel(false)
        }
        return future
    }

    private fun streamPreferences(): PlaybackStreamPreferences {
        val current = settings ?: getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
        val storedHeight = current.getInt("playback_height", PlaybackStreamPreferences().maxHeight)
        val height = storedHeight.takeIf { it in PlaybackStreamPreferences.SUPPORTED_HEIGHTS }
            ?: PlaybackStreamPreferences().maxHeight
        return PlaybackStreamPreferences(
            maxHeight = height,
            h264Only = current.getBoolean("playback_h264", true),
            fps30Only = current.getBoolean("playback_fps30", true),
            audioOnly = current.getBoolean("playback_audio_only", false),
        )
    }

    private fun resumePositionMs(checkpoint: PlaybackSession): Long {
        if (!getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE).getBoolean("resume_playback", true) ||
            checkpoint.localAudioTrack != null
        ) return 0L
        val library = LocalLibraryRepository(applicationContext)
        return try {
            library.resumePositionMs(checkpoint.video.id).coerceAtLeast(0L)
        } finally {
            library.close()
        }
    }

    private fun resumptionMediaItem(
        checkpoint: PlaybackSession,
        stream: ResolvedVideoStream?,
        includeLocalUri: Boolean,
        includeArtwork: Boolean,
    ): MediaItem {
        val video = stream?.video ?: checkpoint.video
        val audioTrack = checkpoint.localAudioTrack
        val metadata = MediaMetadata.Builder()
            .setTitle(audioTrack?.title ?: video.title)
            .setArtist(video.channel)
            .apply {
                if (includeArtwork) PlaybackArtworkPolicy.resolve(video)?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        val builder = MediaItem.Builder()
            .setMediaId(video.id)
            .setMediaMetadata(metadata)

        if (!includeLocalUri) return builder.build()
        if (audioTrack != null) {
            return builder
                .setUri(Uri.parse(audioTrack.uri))
                .setMimeType(audioTrack.mimeType)
                .build()
        }
        val localVideo = checkpoint.localVideoSource
        if (localVideo != null) {
            return builder
                .setUri(Uri.parse(localVideo.uri))
                .setMimeType(localVideo.mimeType)
                .build()
        }

        val resolved = requireNotNull(stream) { "A playable stream was not resolved." }
        builder.setUri(resolved.uri)
        if (resolved.isDashManifest || resolved.mimeType.contains("dash", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD)
        } else if (resolved.isHlsManifest || resolved.mimeType.contains("mpegurl", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }
}

/** Merges a user-selected local audio file into the video item while keeping source resolution local. */
internal class ExternalAudioMediaSourceFactory(
    private val delegate: DefaultMediaSourceFactory,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
    ): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val extras = mediaItem.mediaMetadata.extras ?: return delegate.createMediaSource(mediaItem)
        val audioUri = extras.getString(EXTERNAL_AUDIO_URI).orEmpty()
        if (audioUri.isBlank()) return delegate.createMediaSource(mediaItem)

        val videoExtras = Bundle(extras).apply {
            remove(EXTERNAL_AUDIO_URI)
            remove(EXTERNAL_AUDIO_MIME_TYPE)
            remove(EXTERNAL_AUDIO_LABEL)
        }
        val videoMetadata = mediaItem.mediaMetadata.buildUpon().setExtras(videoExtras).build()
        val videoItem = mediaItem.buildUpon().setMediaMetadata(videoMetadata).build()
        val videoSource = FilteringMediaSource(
            delegate.createMediaSource(videoItem),
            setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT),
        )
        val audioBuilder = MediaItem.Builder().setUri(Uri.parse(audioUri))
        extras.getString(EXTERNAL_AUDIO_MIME_TYPE)?.takeIf(String::isNotBlank)?.let(audioBuilder::setMimeType)
        val audioSource = FilteringMediaSource(
            delegate.createMediaSource(audioBuilder.build()),
            setOf(C.TRACK_TYPE_AUDIO),
        )
        return MergingMediaSource(true, true, videoSource, audioSource)
    }
}

internal const val EXTERNAL_AUDIO_URI = "com.lowtube.android.external_audio.uri"
internal const val EXTERNAL_AUDIO_MIME_TYPE = "com.lowtube.android.external_audio.mime_type"
internal const val EXTERNAL_AUDIO_LABEL = "com.lowtube.android.external_audio.label"

internal fun configurePlaybackPreload(player: ExoPlayer, enabled: Boolean) {
    player.setPreloadConfiguration(
        if (enabled) ExoPlayer.PreloadConfiguration(NEXT_ITEM_PRELOAD_DURATION_US)
        else ExoPlayer.PreloadConfiguration.DEFAULT,
    )
}

private fun removeFutureItems(player: ExoPlayer) {
    val currentIndex = player.currentMediaItemIndex
    if (currentIndex >= 0 && player.mediaItemCount > currentIndex + 1) {
        player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
    }
}

private const val PREF_PRELOAD_NEXT = "playback_preload_next"
private const val PREF_AUTO_NEXT = "playback_auto_next"
private const val NEXT_ITEM_PRELOAD_DURATION_US = 5_000_000L

internal fun createPlaybackLoadControl(context: Context): DefaultLoadControl {
    val preferences = context.getSharedPreferences("lowtube_settings", Context.MODE_PRIVATE)
    val bufferPreferences = PlaybackBufferPreferences.fromStoredValues(
        maximumSeconds = preferences.getInt("playback_buffer_seconds", PlaybackBufferPreferences.DEFAULT_MAXIMUM_SECONDS),
        memoryLimitMiB = preferences.getInt("playback_buffer_mib", PlaybackBufferPreferences.DEFAULT_MEMORY_LIMIT_MIB),
        backBufferMiB = preferences.getInt("playback_back_buffer_mib", PlaybackBufferPreferences.DEFAULT_BACK_BUFFER_MIB),
        startupSeconds = preferences.getInt("playback_start_buffer_seconds", PlaybackBufferPreferences.DEFAULT_STARTUP_SECONDS),
    )
    val bufferPolicy = bufferPreferences.toMedia3Policy()
    return DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(
            bufferPolicy.minimumBufferMs,
            bufferPolicy.maximumBufferMs,
            bufferPolicy.startupBufferMs,
            bufferPolicy.startupBufferMs,
        )
        .setTargetBufferBytes(bufferPolicy.targetBufferBytes)
        .setBackBuffer(bufferPolicy.backBufferDurationMs, true)
        .setPrioritizeTimeOverSizeThresholdsForStreaming(false)
        .build()
}

internal fun createPlaybackDataSourceFactory(context: Context): DataSource.Factory {
    val httpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    )
    return DefaultDataSource.Factory(context, httpDataSourceFactory)
}
