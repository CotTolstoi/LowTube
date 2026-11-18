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
import androidx.media3.common.Player
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
import com.lowtube.android.data.PlaybackBufferPreferences

/** Owns Android media playback independently of the Activity lifecycle. */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var settings: SharedPreferences? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

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
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) removeFutureItems(player)
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
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        settingsListener?.let { listener -> settings?.unregisterOnSharedPreferenceChangeListener(listener) }
        settingsListener = null
        settings = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
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
