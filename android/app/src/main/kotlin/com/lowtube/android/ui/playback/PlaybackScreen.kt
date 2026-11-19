package com.lowtube.android.ui.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MimeTypes.VIDEO_H264
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.lowtube.android.R
import com.lowtube.android.data.InnerTubeSearchRepository
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.PlaybackStreamPreferences
import com.lowtube.android.model.LocalAudioTrack
import com.lowtube.android.model.LocalVideoSource
import com.lowtube.android.model.QueueEntry
import com.lowtube.android.model.ResolvedVideoStream
import com.lowtube.android.model.VideoItem
import com.lowtube.android.playback.PlaybackService
import com.lowtube.android.playback.PlaybackDecoderDiagnostics
import com.lowtube.android.playback.EXTERNAL_AUDIO_LABEL
import com.lowtube.android.playback.EXTERNAL_AUDIO_MIME_TYPE
import com.lowtube.android.playback.EXTERNAL_AUDIO_URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    video: VideoItem,
    playbackRequestId: Long,
    isFollowing: Boolean,
    onToggleFollow: (VideoItem) -> Boolean,
    localAudioTrack: LocalAudioTrack?,
    localVideoSource: LocalVideoSource? = null,
    library: LocalLibraryRepository,
    playbackPreferences: PlaybackStreamPreferences,
    playbackSpeed: Float,
    resumePlayback: Boolean,
    autoRecover: Boolean = true,
    preloadNext: Boolean,
    onPlaybackSpeedChanged: (Float) -> Unit,
    autoNext: Boolean,
    hasQueuedItems: Boolean,
    queueHeadRowId: Long?,
    onResolveNextQueueEntry: suspend () -> QueueEntry?,
    onIsQueueHead: (Long) -> Boolean,
    onPreloadedQueueEntryStarted: (QueueEntry) -> Unit,
    onPlaybackReady: () -> Unit,
    onAdvanceQueue: () -> Unit,
    onPreviousVideo: () -> Unit,
    onBack: () -> Unit,
    canNavigateShortsBack: Boolean = false,
    canNavigateShortsForward: Boolean = false,
    onNavigateShorts: (Int) -> Unit = {},
    inPictureInPictureMode: Boolean = false,
    onPictureInPictureEligibilityChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember(context) { InnerTubeSearchRepository(context.applicationContext) }
    val decoderPreferences = remember(context) {
        context.getSharedPreferences(PlaybackDecoderDiagnostics.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var videoPlayerView by remember(video.id, playbackRequestId) { mutableStateOf<PlayerView?>(null) }
    var seekFeedbackDirection by remember(video.id, playbackRequestId) { mutableIntStateOf(0) }
    var seekFeedbackSequence by remember(video.id, playbackRequestId) { mutableIntStateOf(0) }
    var decoderSnapshot by remember(video.id, playbackRequestId) { mutableStateOf(PlaybackDecoderDiagnostics.read(decoderPreferences)) }
    var message by remember { mutableStateOf<String?>(null) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var audioMenuExpanded by remember { mutableStateOf(false) }
    var subtitleMenuExpanded by remember { mutableStateOf(false) }
    var externalAudioUri by rememberSaveable(video.id, localAudioTrack?.uri) { mutableStateOf("") }
    var externalAudioLabel by rememberSaveable(video.id, localAudioTrack?.uri) { mutableStateOf("") }
    var externalSubtitleUri by rememberSaveable(video.id, localAudioTrack?.uri) { mutableStateOf("") }
    var availableAudioTracks by remember { mutableStateOf(emptyList<TrackOption>()) }
    var availableSubtitleTracks by remember { mutableStateOf(emptyList<TrackOption>()) }
    var loading by remember(video.id, playbackRequestId) { mutableStateOf(true) }
    var shouldResumePlayback by rememberSaveable(video.id, playbackRequestId, localAudioTrack?.uri) { mutableStateOf(true) }
    var watchedVideo by remember(video.id, playbackRequestId) { mutableStateOf(video) }
    var displayedFollowState by remember(video.id, playbackRequestId) { mutableStateOf(isFollowing) }
    var readyNotified by remember(video.id, playbackRequestId) { mutableStateOf(false) }
    var endedHandled by remember(video.id, playbackRequestId) { mutableStateOf(false) }
    var recoveryAttempts by remember(video.id, playbackRequestId, localAudioTrack?.uri) { mutableStateOf(0) }
    var recoveryInProgress by remember(video.id, playbackRequestId, localAudioTrack?.uri) { mutableStateOf(false) }
    var recoveryJob by remember(video.id, playbackRequestId, localAudioTrack?.uri) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var recoveryCompletion by remember(video.id, playbackRequestId, localAudioTrack?.uri) {
        mutableStateOf<CompletableDeferred<PlaybackException?>?>(null)
    }
    var preloadAttemptedVideoId by remember { mutableStateOf<String?>(null) }
    var stagedQueueEntry by remember { mutableStateOf<QueueEntry?>(null) }
    var stagedQueueJob by remember { mutableStateOf<Job?>(null) }
    var resolvingQueueRowId by remember { mutableStateOf<Long?>(null) }
    var lastObservedDurationMs by remember(video.id, playbackRequestId) { mutableStateOf(0L) }
    val playbackScope = rememberCoroutineScope()
    LaunchedEffect(isFollowing, video.id, playbackRequestId) {
        displayedFollowState = isFollowing
    }
    val latestAutoRecover by rememberUpdatedState(autoRecover)
    val latestAutoNext by rememberUpdatedState(autoNext)
    val latestPreloadNext by rememberUpdatedState(preloadNext)
    val latestHasQueuedItems by rememberUpdatedState(hasQueuedItems)
    val immersiveShorts = video.kind == "shorts" && localAudioTrack == null && !playbackPreferences.audioOnly

    val seekBy: (Long) -> Unit = { offsetMs ->
        val activeController = controller
        if (activeController != null && activeController.isCurrentMediaItemSeekable) {
            val target = PlaybackSeekPolicy.target(
                currentPositionMs = activeController.currentPosition,
                durationMs = activeController.duration,
                offsetMs = offsetMs,
            )
            if (target != null) {
                activeController.seekTo(target)
                seekFeedbackDirection = if (offsetMs < 0L) -1 else 1
                seekFeedbackSequence++
            }
        }
    }

    LaunchedEffect(seekFeedbackSequence) {
        if (seekFeedbackSequence == 0) return@LaunchedEffect
        val sequence = seekFeedbackSequence
        delay(650)
        if (seekFeedbackSequence == sequence) seekFeedbackDirection = 0
    }

    DisposableEffect(decoderPreferences) {
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, _ ->
            val snapshot = PlaybackDecoderDiagnostics.read(preferences)
            mainHandler.post { decoderSnapshot = snapshot }
        }
        decoderPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { decoderPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val latestResolveNextQueueEntry by rememberUpdatedState(onResolveNextQueueEntry)
    val latestIsQueueHead by rememberUpdatedState(onIsQueueHead)
    val latestOnPreloadedStarted by rememberUpdatedState(onPreloadedQueueEntryStarted)
    val latestPlaybackSpeed by rememberUpdatedState(playbackSpeed)
    val latestPlaybackPreferences by rememberUpdatedState(playbackPreferences)
    val latestPlaybackRequestId by rememberUpdatedState(playbackRequestId)
    val genericTrackLabel = stringResource(R.string.track_generic)
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val configuration = externalAudioConfiguration(context, uri)
        if (configuration == null) {
            message = context.getString(R.string.external_audio_unsupported)
            return@rememberLauncherForActivityResult
        }
        val alreadyPersisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        val canPersist = alreadyPersisted || runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
        if (!canPersist) {
            message = context.getString(R.string.subtitles_permission_error)
            return@rememberLauncherForActivityResult
        }
        if (!replaceExternalAudio(controller, configuration)) {
            if (!alreadyPersisted) runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            message = context.getString(R.string.subtitles_player_unavailable)
            return@rememberLauncherForActivityResult
        }
        val previous = externalAudioUri
        externalAudioUri = uri.toString()
        externalAudioLabel = configuration.label
        message = context.getString(R.string.external_audio_loaded, configuration.label)
        if (previous.isNotBlank() && previous != uri.toString()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val configuration = externalSubtitleConfiguration(context, uri)
        if (configuration == null) {
            message = context.getString(R.string.subtitles_unsupported_file)
            return@rememberLauncherForActivityResult
        }
        val alreadyPersisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        val canPersist = alreadyPersisted || runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
        if (!canPersist) {
            message = context.getString(R.string.subtitles_permission_error)
            return@rememberLauncherForActivityResult
        }
        if (!replaceExternalSubtitle(controller, configuration)) {
            if (!alreadyPersisted) runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            message = context.getString(R.string.subtitles_player_unavailable)
            return@rememberLauncherForActivityResult
        }
        val previous = externalSubtitleUri
        externalSubtitleUri = uri.toString()
        message = context.getString(R.string.subtitles_loaded)
        if (previous.isNotBlank() && previous != uri.toString()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(previous),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }
    val errorListener = remember(video.id, playbackRequestId, localAudioTrack?.uri, localVideoSource?.uri, playbackPreferences.audioOnly, autoNext, onPlaybackReady, onAdvanceQueue, onPreloadedQueueEntryStarted) {
        object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val active = controller ?: return
                availableAudioTracks = trackOptions(tracks, C.TRACK_TYPE_AUDIO, active, genericTrackLabel)
                availableSubtitleTracks = trackOptions(tracks, C.TRACK_TYPE_TEXT, active, genericTrackLabel)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val staged = stagedQueueEntry ?: return
                if (mediaItem?.mediaId != staged.video.id) return
                val active = controller ?: return
                if (externalAudioUri.isNotBlank()) {
                    val previousAudio = externalAudioUri
                    externalAudioUri = ""
                    externalAudioLabel = ""
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(previousAudio), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                if (lastObservedDurationMs > 0) {
                    library.recordWatchProgress(watchedVideo, lastObservedDurationMs, lastObservedDurationMs, complete = true)
                }
                val previousItemCount = active.currentMediaItemIndex
                if (previousItemCount > 0) active.removeMediaItems(0, previousItemCount)
                watchedVideo = staged.video
                stagedQueueEntry = null
                stagedQueueJob?.cancel()
                stagedQueueJob = null
                preloadAttemptedVideoId = null
                latestOnPreloadedStarted(staged)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                onPictureInPictureEligibilityChanged(
                    localAudioTrack == null && !playbackPreferences.audioOnly &&
                        playbackState == Player.STATE_READY && controller?.isPlaying == true &&
                        controller?.currentMediaItem != null,
                )
                if (playbackState == Player.STATE_READY) {
                    controller?.duration?.takeIf { it > 0 }?.let { lastObservedDurationMs = it }
                }
                if (playbackState == Player.STATE_READY && recoveryInProgress) {
                    recoveryInProgress = false
                    loading = false
                    message = null
                    recoveryCompletion?.complete(null)
                }
                if (playbackState == Player.STATE_READY && !readyNotified) {
                    readyNotified = true
                    onPlaybackReady()
                }
                if (playbackState == Player.STATE_ENDED) {
                    if (localAudioTrack == null) controller?.let { active ->
                        library.recordWatchProgress(
                            watchedVideo,
                            active.currentPosition.coerceAtLeast(0),
                            active.duration.coerceAtLeast(0),
                            complete = true,
                        )
                    }
                    if (localAudioTrack == null && autoNext && !endedHandled) {
                        endedHandled = true
                        onAdvanceQueue()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val active = controller
                onPictureInPictureEligibilityChanged(
                    isPlaying && localAudioTrack == null && !playbackPreferences.audioOnly &&
                        active?.playbackState == Player.STATE_READY && active.currentMediaItem != null,
                )
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                shouldResumePlayback = playWhenReady
            }

            override fun onPlayerError(error: PlaybackException) {
                onPictureInPictureEligibilityChanged(false)
                recoveryCompletion?.let { pending ->
                    if (!pending.isCompleted) {
                        pending.complete(error)
                        return
                    }
                }
                message = PlaybackFailureMessages.text(context, error)
                if (!PlaybackRecoveryPolicy.shouldRetryPlayerError(
                        latestAutoRecover,
                        recoveryAttempts,
                        error.errorCode,
                ) || localAudioTrack != null || localVideoSource != null || recoveryJob?.isActive == true
                ) return

                val activeController = controller ?: return
                var resumePositionMs = activeController.currentPosition.coerceAtLeast(0)
                val wasPlaying = activeController.playWhenReady
                recoveryInProgress = true
                loading = true
                recoveryJob = playbackScope.launch {
                    var currentError: PlaybackException? = error
                    var lastFailure: Throwable? = null
                    var recovered = false
                    try {
                        while (recoveryAttempts < PlaybackRecoveryPolicy.MAX_RETRIES && currentError != null) {
                            val nextAttempt = recoveryAttempts + 1
                            if (!PlaybackRecoveryPolicy.shouldRetryPlayerError(
                                    latestAutoRecover,
                                    recoveryAttempts,
                                    currentError.errorCode,
                                )
                            ) break
                            recoveryAttempts = nextAttempt
                            message = context.getString(
                                R.string.recovering_playback,
                                nextAttempt,
                                PlaybackRecoveryPolicy.MAX_RETRIES,
                            )
                            delay(PlaybackRecoveryPolicy.retryDelayMillis(nextAttempt))

                            val stream = try {
                                repository.resolvePlayback(watchedVideo, latestPlaybackPreferences)
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                lastFailure = failure
                                currentError = null
                                if (PlaybackRecoveryPolicy.shouldRetryResolution(
                                        latestAutoRecover,
                                        recoveryAttempts,
                                        failure,
                                    )
                                ) {
                                    // A fresh-link resolution can consume the next bounded attempt.
                                    currentError = error
                                }
                                continue
                            }

                            watchedVideo = stream.video
                            resumePositionMs = activeController.currentPosition.coerceAtLeast(resumePositionMs)
                            val pending = CompletableDeferred<PlaybackException?>()
                            recoveryCompletion = pending
                            activeController.setPlaybackParameters(
                                PlaybackParameters(latestPlaybackSpeed.coerceIn(0.25f, 2f)),
                            )
                            applyPlaybackStreamPreferences(activeController, latestPlaybackPreferences)
                            val externalAudio = externalAudioConfiguration(activeController.currentMediaItem)
                            activeController.setMediaItem(
                                remoteMediaItem(stream, externalAudio, latestPlaybackRequestId),
                                resumePositionMs,
                            )
                            activeController.prepare()
                            if (wasPlaying) activeController.play() else activeController.pause()
                            currentError = pending.await()
                            recoveryCompletion = null
                            if (currentError == null) {
                                recovered = true
                                break
                            }
                            resumePositionMs = activeController.currentPosition.coerceAtLeast(resumePositionMs)
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        lastFailure = failure
                    } finally {
                        recoveryCompletion = null
                        recoveryInProgress = false
                        loading = false
                        if (!recovered) {
                            message = PlaybackFailureMessages.text(context, lastFailure ?: currentError)
                        }
                    }
                }
            }
        }
    }
    val controllerFuture = remember(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        MediaController.Builder(context, token).buildAsync()
    }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }
                    .onFailure { message = PlaybackFailureMessages.text(context, it); loading = false }
                    .onSuccess { controller = it }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            MediaController.releaseFuture(controllerFuture)
        }
    }

    DisposableEffect(controller, errorListener) {
        controller?.addListener(errorListener)
        onDispose {
            controller?.removeListener(errorListener)
        }
    }

    DisposableEffect(controller, video.id, playbackRequestId, localAudioTrack?.uri, localVideoSource?.uri) {
        onDispose {
            onPictureInPictureEligibilityChanged(false)
            recoveryJob?.cancel()
            recoveryCompletion?.cancel()
            controller?.let { active ->
                stagedQueueJob?.cancel()
                stagedQueueJob = null
                stagedQueueEntry?.let { removeFutureMediaItem(active, it.video.id) }
                stagedQueueEntry = null
                resolvingQueueRowId = null
                if (localAudioTrack == null && active.currentMediaItem != null && active.currentPosition > 0) {
                    library.recordWatchProgress(watchedVideo, active.currentPosition, active.duration.coerceAtLeast(0))
                }
            }
        }
    }

    LaunchedEffect(controller, video.id, playbackRequestId, localAudioTrack?.uri, localVideoSource?.uri, resumePlayback) {
        val activeController = controller ?: return@LaunchedEffect
        loading = true
        message = null
        try {
            val currentMediaItem = activeController.currentMediaItem
            if (PlaybackRequestPolicy.shouldReuseCurrentItem(
                    currentMediaId = currentMediaItem?.mediaId,
                    currentRequestId = PlaybackRequestPolicy.requestId(currentMediaItem?.mediaMetadata?.extras),
                    requestedMediaId = video.id,
                    requestedRequestId = playbackRequestId,
                    playbackState = activeController.playbackState,
                )
            ) {
                watchedVideo = video
                loading = false
                readyNotified = true
                shouldResumePlayback = activeController.playWhenReady
                onPlaybackReady()
                return@LaunchedEffect
            }
            var stream: ResolvedVideoStream? = null
            var resolutionRetries = 0
            PlaybackDecoderDiagnostics.clear(context)
            if (localAudioTrack == null && localVideoSource == null) {
                while (stream == null) {
                    stream = try {
                        repository.resolvePlayback(video, playbackPreferences)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        if (!PlaybackRecoveryPolicy.shouldRetryResolution(autoRecover, resolutionRetries, failure)) {
                            throw failure
                        }
                        resolutionRetries++
                        message = context.getString(
                            R.string.recovering_playback,
                            resolutionRetries,
                            PlaybackRecoveryPolicy.MAX_RETRIES,
                        )
                        delay(PlaybackRecoveryPolicy.retryDelayMillis(resolutionRetries))
                        null
                    }
                }
                message = null
            }
            if (stream != null) {
                watchedVideo = stream.video
                applyPlaybackStreamPreferences(activeController, playbackPreferences)
            }
            activeController.setPlaybackParameters(PlaybackParameters(playbackSpeed.coerceIn(0.25f, 2f)))
            val item = if (localAudioTrack != null) {
                val metadata = MediaMetadata.Builder().setTitle(localAudioTrack.title)
                    .setExtras(Bundle().apply { putLong(PLAYBACK_REQUEST_ID_EXTRA, playbackRequestId) }).build()
                MediaItem.Builder().setUri(localAudioTrack.uri).setMimeType(localAudioTrack.mimeType)
                    .setMediaMetadata(metadata).build()
            } else if (localVideoSource != null) {
                val metadata = MediaMetadata.Builder().setTitle(video.title)
                    .setArtist(video.channel)
                    .setExtras(Bundle().apply { putLong(PLAYBACK_REQUEST_ID_EXTRA, playbackRequestId) }).build()
                MediaItem.Builder().setMediaId(video.id)
                    .setUri(localVideoSource.uri)
                    .setMimeType(localVideoSource.mimeType)
                    .setMediaMetadata(metadata)
                    .build()
            } else remoteMediaItem(
                stream!!,
                externalAudioUri.takeIf(String::isNotBlank)?.let(Uri::parse)?.let { externalAudioConfiguration(context, it) },
                playbackRequestId,
            )
            val resumePositionMs = PlaybackResumePolicy.position(resumePlayback, localAudioTrack != null) {
                library.resumePositionMs(video.id)
            }
            activeController.setMediaItem(item, resumePositionMs)
            activeController.prepare()
            if (shouldResumePlayback) activeController.play() else activeController.pause()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            message = PlaybackFailureMessages.text(context, failure)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(video.id, playbackRequestId) {
        preloadAttemptedVideoId = null
    }

    LaunchedEffect(controller, video.id, playbackRequestId, queueHeadRowId) {
        val activeController = controller ?: return@LaunchedEffect
        val staged = stagedQueueEntry
        if (staged != null && staged.rowId != queueHeadRowId) {
            removeFutureMediaItem(activeController, staged.video.id)
            stagedQueueEntry = null
            preloadAttemptedVideoId = video.id
        }
        val resolving = resolvingQueueRowId
        if (resolving != null && resolving != queueHeadRowId) {
            stagedQueueJob?.cancel()
            stagedQueueJob = null
            resolvingQueueRowId = null
            preloadAttemptedVideoId = video.id
        }
    }

    LaunchedEffect(controller, video.id, playbackRequestId, autoNext, preloadNext, localAudioTrack?.uri) {
        val activeController = controller ?: return@LaunchedEffect
        if (!autoNext || !preloadNext || localAudioTrack != null) {
            stagedQueueJob?.cancel()
            stagedQueueJob = null
            resolvingQueueRowId = null
            stagedQueueEntry?.let { removeFutureMediaItem(activeController, it.video.id) }
            stagedQueueEntry = null
            return@LaunchedEffect
        }
    }

    LaunchedEffect(controller, video.id, playbackRequestId, localAudioTrack?.uri) {
        val activeController = controller ?: return@LaunchedEffect
        if (localAudioTrack != null) return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            val isBuffering = activeController.playWhenReady && activeController.playbackState == Player.STATE_BUFFERING
            if (isBuffering) {
                val hadPreparation = stagedQueueEntry != null || stagedQueueJob?.isActive == true
                stagedQueueJob?.cancel()
                stagedQueueJob = null
                resolvingQueueRowId = null
                stagedQueueEntry?.let { removeFutureMediaItem(activeController, it.video.id) }
                stagedQueueEntry = null
                if (hadPreparation) preloadAttemptedVideoId = video.id
                continue
            }
            val staged = stagedQueueEntry
            if (staged != null && activeController.mediaItemCount <= activeController.currentMediaItemIndex + 1) {
                stagedQueueEntry = null
                preloadAttemptedVideoId = video.id
            }
            val durationMs = activeController.duration.coerceAtLeast(0)
            if (durationMs > 0) lastObservedDurationMs = durationMs
            val remainingMs = if (durationMs > 0) durationMs - activeController.currentPosition.coerceAtLeast(0) else 0
            if (!PlaybackPreloadPolicy.shouldStart(
                    enabled = latestPreloadNext,
                    autoNext = latestAutoNext,
                    hasQueuedItem = latestHasQueuedItems,
                    isLocalAudio = false,
                    isLive = video.kind == "live" || video.liveStatus in setOf("is_live", "is_upcoming"),
                    isPlaying = activeController.isPlaying && activeController.currentMediaItem?.mediaId == video.id,
                    isBuffering = activeController.playbackState == Player.STATE_BUFFERING,
                    remainingMs = remainingMs,
                    attemptedForCurrentItem = preloadAttemptedVideoId == video.id,
                ) || stagedQueueEntry != null || stagedQueueJob?.isActive == true
            ) continue

            preloadAttemptedVideoId = video.id
            val job = playbackScope.launch {
                var candidate: QueueEntry? = null
                val ownJob = currentCoroutineContext()[Job]
                try {
                    candidate = latestResolveNextQueueEntry() ?: return@launch
                    resolvingQueueRowId = candidate.rowId
                    if (!latestIsQueueHead(candidate.rowId)) return@launch
                    val stream = withTimeout(25_000) {
                        repository.resolvePlayback(candidate.video, latestPlaybackPreferences)
                    }
                    if (!latestAutoNext || !latestPreloadNext || !latestIsQueueHead(candidate.rowId)) return@launch
                    if (activeController.currentMediaItem?.mediaId != video.id || !activeController.isPlaying ||
                        activeController.playbackState == Player.STATE_BUFFERING
                    ) return@launch
                    if (activeController.mediaItemCount > activeController.currentMediaItemIndex + 1) {
                        activeController.removeMediaItems(activeController.currentMediaItemIndex + 1, activeController.mediaItemCount)
                    }
                    activeController.addMediaItem(remoteMediaItem(stream, playbackRequestId = latestPlaybackRequestId))
                    stagedQueueEntry = candidate
                    Log.i("LowTubePlayback", "next queue item prepared")
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    Log.i("LowTubePlayback", "next queue preparation skipped: ${failure.javaClass.simpleName}")
                } finally {
                    if (stagedQueueJob === ownJob) stagedQueueJob = null
                    if (candidate == null || resolvingQueueRowId == candidate.rowId) resolvingQueueRowId = null
                }
            }
            stagedQueueJob = job
        }
    }

    LaunchedEffect(controller, video.id, playbackRequestId, localAudioTrack?.uri) {
        val activeController = controller ?: return@LaunchedEffect
        if (localAudioTrack != null) return@LaunchedEffect
        while (true) {
            delay(3_000)
            if (activeController.currentMediaItem != null && activeController.playbackState == Player.STATE_READY && activeController.currentPosition > 0) {
                library.recordWatchProgress(watchedVideo, activeController.currentPosition, activeController.duration.coerceAtLeast(0))
            }
        }
    }

    if (inPictureInPictureMode) {
        Box(Modifier.fillMaxSize()) {
            if (!playbackPreferences.audioOnly && localAudioTrack == null) controller?.let { activeController ->
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext: Context -> PlayerView(viewContext).apply { player = activeController; useController = true } },
                    update = { it.player = activeController },
                )
            }
        }
        return
    }

    Scaffold(topBar = {
        if (!immersiveShorts) TopAppBar(
            title = { Text(stringResource(R.string.now_playing)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
        )
    }) { padding ->
        if (immersiveShorts) {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(padding)) {
                var verticalDrag by remember(video.id, playbackRequestId) { mutableStateOf(0f) }
                val swipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
                controller?.let { activeController ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext: Context -> PlayerView(viewContext).apply { player = activeController; useController = true } },
                        update = { it.player = activeController },
                    )
                }
                Box(
                    Modifier.fillMaxSize().pointerInput(
                        video.id, playbackRequestId, canNavigateShortsBack, canNavigateShortsForward,
                    ) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                when {
                                    verticalDrag <= -swipeThresholdPx && canNavigateShortsForward -> {
                                        controller?.let { active ->
                                            if (active.currentPosition > 0) {
                                                library.recordWatchProgress(
                                                    watchedVideo, active.currentPosition, active.duration.coerceAtLeast(0),
                                                )
                                            }
                                        }
                                        onNavigateShorts(1)
                                    }
                                    verticalDrag >= swipeThresholdPx && canNavigateShortsBack -> {
                                        controller?.let { active ->
                                            if (active.currentPosition > 0) {
                                                library.recordWatchProgress(
                                                    watchedVideo, active.currentPosition, active.duration.coerceAtLeast(0),
                                                )
                                            }
                                        }
                                        onNavigateShorts(-1)
                                    }
                                }
                                verticalDrag = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                verticalDrag += dragAmount
                                change.consume()
                            },
                        )
                    },
                )
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(top = 12.dp, start = 8.dp),
                ) { Text(stringResource(R.string.back), color = Color.White) }
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 112.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (watchedVideo.channelId.matches(Regex("UC[a-zA-Z0-9_-]{22}"))) {
                        ChannelFollowControl(isFollowing = displayedFollowState) {
                            displayedFollowState = onToggleFollow(watchedVideo)
                        }
                    }
                    TextButton(onClick = {
                        library.saveToWatchLater(watchedVideo)
                        android.widget.Toast.makeText(context, R.string.saved_to_watch_later, android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.shorts_save), color = Color.White) }
                    TextButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, watchedVideo.url)
                        }
                        context.startActivity(Intent.createChooser(share, context.getString(R.string.share_link)))
                    }) { Text(stringResource(R.string.share_link), color = Color.White) }
                }
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(end = 176.dp, bottom = 80.dp).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.88f)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(watchedVideo.channel, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(
                        watchedVideo.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                message?.let { Text(it, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), color = Color.White) }
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if ((playbackPreferences.audioOnly && localVideoSource == null) || localAudioTrack != null) {
                Text(stringResource(R.string.audio_only_playback), style = MaterialTheme.typography.titleMedium)
            }
            if ((localVideoSource != null || !playbackPreferences.audioOnly) && localAudioTrack == null) controller?.let { activeController ->
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext: Context ->
                            PlayerView(viewContext).apply {
                                player = activeController
                                useController = true
                                videoPlayerView = this
                            }
                        },
                        update = {
                            it.player = activeController
                            videoPlayerView = it
                        },
                    )
                    if (!immersiveShorts) {
                        PlaybackSeekGestureOverlay(
                            onShowControls = { videoPlayerView?.showController() },
                            onSeek = seekBy,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        if (seekFeedbackDirection != 0) {
                            Text(
                                text = stringResource(
                                    if (seekFeedbackDirection < 0) R.string.seek_backward_feedback
                                    else R.string.seek_forward_feedback,
                                ),
                                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.72f))
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }
            }
            Text(watchedVideo.title, style = MaterialTheme.typography.titleLarge)
            if (watchedVideo.channel.isNotBlank()) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(watchedVideo.channel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (localAudioTrack == null && watchedVideo.channelId.matches(Regex("UC[a-zA-Z0-9_-]{22}"))) {
                        ChannelFollowControl(isFollowing = displayedFollowState) {
                            displayedFollowState = onToggleFollow(watchedVideo)
                        }
                    }
                }
            }
            if (localAudioTrack == null) androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPreviousVideo, enabled = !loading) { Text(stringResource(R.string.previous_video)) }
                if (hasQueuedItems) TextButton(onClick = onAdvanceQueue, enabled = !loading) { Text(stringResource(R.string.next_in_queue)) }
            }
            if (localAudioTrack == null && (localVideoSource != null || !playbackPreferences.audioOnly)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.playback_diagnostics), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.video_decoder_name,
                            decoderSnapshot.name.ifBlank { context.getString(R.string.video_decoder_waiting) },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (decoderSnapshot.inputFormat.isNotBlank()) {
                        Text(stringResource(R.string.video_input_format, decoderSnapshot.inputFormat), style = MaterialTheme.typography.bodySmall)
                    }
                    val hardwareStatus = when (decoderSnapshot.hardwareStatus) {
                        PlaybackDecoderDiagnostics.HARDWARE_STATUS_HARDWARE -> R.string.video_hardware_yes
                        PlaybackDecoderDiagnostics.HARDWARE_STATUS_SOFTWARE -> R.string.video_hardware_no
                        else -> R.string.video_hardware_unknown
                    }
                    Text(stringResource(R.string.video_hardware_status, stringResource(hardwareStatus)), style = MaterialTheme.typography.bodySmall)
                }
            }
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.playback_speed), style = MaterialTheme.typography.bodyLarge)
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { speedMenuExpanded = true }) {
                        Text("${"%.2f".format(java.util.Locale.ROOT, playbackSpeed).trimEnd('0').trimEnd('.')}×")
                    }
                    DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                        (1..8).map { it * 0.25f }.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("${"%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.')}×") },
                                onClick = {
                                    speedMenuExpanded = false
                                    onPlaybackSpeedChanged(speed)
                                    controller?.setPlaybackParameters(PlaybackParameters(speed))
                                },
                            )
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Box {
                    TextButton(
                        onClick = { audioMenuExpanded = true },
                        enabled = controller != null && localAudioTrack == null && !playbackPreferences.audioOnly,
                    ) {
                        Text(stringResource(R.string.audio_track))
                    }
                    DropdownMenu(expanded = audioMenuExpanded, onDismissRequest = { audioMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = {
                                if (externalAudioUri.isBlank()) Text(stringResource(R.string.external_audio_choose))
                                else Text(stringResource(R.string.external_audio_loaded_menu, externalAudioLabel))
                            },
                            enabled = controller != null && localAudioTrack == null && !playbackPreferences.audioOnly,
                            onClick = {
                                audioMenuExpanded = false
                                audioPicker.launch(arrayOf("audio/*", "application/octet-stream"))
                            },
                        )
                        if (externalAudioUri.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.external_audio_remove)) },
                                onClick = {
                                    audioMenuExpanded = false
                                    if (replaceExternalAudio(controller, null)) {
                                        val previous = externalAudioUri
                                        externalAudioUri = ""
                                        externalAudioLabel = ""
                                        message = null
                                        runCatching {
                                            context.contentResolver.releasePersistableUriPermission(
                                                Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                            )
                                        }
                                    } else message = context.getString(R.string.subtitles_player_unavailable)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.track_auto)) },
                            onClick = {
                                audioMenuExpanded = false
                                updateTrackOverride(controller, C.TRACK_TYPE_AUDIO, null)
                            },
                        )
                        availableAudioTracks.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    audioMenuExpanded = false
                                    updateTrackOverride(controller, C.TRACK_TYPE_AUDIO, option)
                                },
                            )
                        }
                    }
                }
                androidx.compose.foundation.layout.Box {
                    TextButton(
                        onClick = { subtitleMenuExpanded = true },
                        enabled = controller != null && localAudioTrack == null && !playbackPreferences.audioOnly,
                    ) {
                        Text(stringResource(R.string.subtitles))
                    }
                    DropdownMenu(expanded = subtitleMenuExpanded, onDismissRequest = { subtitleMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(
                                    if (externalSubtitleUri.isBlank()) R.string.subtitles_choose_file
                                    else R.string.subtitles_loaded,
                                ))
                            },
                            enabled = controller != null && localAudioTrack == null,
                            onClick = {
                                subtitleMenuExpanded = false
                                subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
                            },
                        )
                        if (externalSubtitleUri.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subtitles_remove_file)) },
                                onClick = {
                                    subtitleMenuExpanded = false
                                    if (replaceExternalSubtitle(controller, null)) {
                                        val previous = externalSubtitleUri
                                        externalSubtitleUri = ""
                                        message = null
                                        runCatching {
                                            context.contentResolver.releasePersistableUriPermission(
                                                Uri.parse(previous),
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                            )
                                        }
                                    } else message = context.getString(R.string.subtitles_player_unavailable)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.subtitles_off)) },
                            onClick = {
                                subtitleMenuExpanded = false
                                updateTrackOverride(controller, C.TRACK_TYPE_TEXT, null, disable = true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.track_auto)) },
                            onClick = {
                                subtitleMenuExpanded = false
                                updateTrackOverride(controller, C.TRACK_TYPE_TEXT, null)
                            },
                        )
                        availableSubtitleTracks.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    subtitleMenuExpanded = false
                                    updateTrackOverride(controller, C.TRACK_TYPE_TEXT, option)
                                },
                            )
                        }
                    }
                }
            }
            if (loading || controller == null) {
                CircularProgressIndicator()
                Text(stringResource(R.string.resolving_stream))
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun ChannelFollowControl(isFollowing: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(if (isFollowing) R.string.followed else R.string.follow))
    }
}

private data class TrackOption(
    val group: androidx.media3.common.TrackGroup,
    val index: Int,
    val label: String,
)

private fun remoteMediaItem(
    stream: ResolvedVideoStream,
    externalAudio: ExternalAudioConfiguration? = null,
    playbackRequestId: Long? = null,
): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(stream.video.title)
        .setArtist(stream.video.channel)
        .build()
    val withExternalAudio = if (externalAudio == null) metadata else metadataWithExternalAudio(metadata, externalAudio)
    val itemMetadata = playbackRequestId?.let { metadataWithPlaybackRequestId(withExternalAudio, it) } ?: withExternalAudio
    val builder = MediaItem.Builder()
        .setMediaId(stream.video.id)
        .setUri(stream.uri)
        .setMediaMetadata(itemMetadata)
    if (stream.isDashManifest || stream.mimeType.contains("dash", ignoreCase = true)) {
        builder.setMimeType(MimeTypes.APPLICATION_MPD)
    } else if (stream.isHlsManifest || stream.mimeType.contains("mpegurl", ignoreCase = true)) {
        builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    }
    return builder.build()
}

private fun metadataWithPlaybackRequestId(metadata: MediaMetadata, requestId: Long): MediaMetadata {
    val extras = Bundle(metadata.extras ?: Bundle()).apply { putLong(PLAYBACK_REQUEST_ID_EXTRA, requestId) }
    return metadata.buildUpon().setExtras(extras).build()
}

internal data class ExternalAudioConfiguration(
    val uri: Uri,
    val mimeType: String?,
    val label: String,
)

internal fun externalAudioConfiguration(context: Context, uri: Uri): ExternalAudioConfiguration? {
    if (uri.scheme != "content") return null
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
    val inferredMime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") }
        ?: when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "3gp", "3gpp" -> "audio/3gpp"
            else -> null
        }
    val hasAudioMime = context.contentResolver.getType(uri)?.startsWith("audio/") == true
    if (inferredMime == null && !hasAudioMime && name.substringAfterLast('.', "").lowercase() !in
        setOf("mp3", "m4a", "mp4", "aac", "wav", "ogg", "oga", "opus", "flac", "3gp", "3gpp")
    ) return null
    return ExternalAudioConfiguration(uri, inferredMime, name.take(256).ifBlank { uri.lastPathSegment.orEmpty() })
}

private fun externalAudioConfiguration(mediaItem: MediaItem?): ExternalAudioConfiguration? {
    val extras = mediaItem?.mediaMetadata?.extras ?: return null
    val rawUri = extras.getString(EXTERNAL_AUDIO_URI).orEmpty()
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull()?.takeIf { it.scheme == "content" } ?: return null
    return ExternalAudioConfiguration(
        uri = uri,
        mimeType = extras.getString(EXTERNAL_AUDIO_MIME_TYPE),
        label = extras.getString(EXTERNAL_AUDIO_LABEL).orEmpty().ifBlank { uri.lastPathSegment.orEmpty() },
    )
}

private fun metadataWithExternalAudio(
    metadata: MediaMetadata,
    configuration: ExternalAudioConfiguration,
): MediaMetadata {
    val extras = Bundle(metadata.extras ?: Bundle()).apply {
        putString(EXTERNAL_AUDIO_URI, configuration.uri.toString())
        putString(EXTERNAL_AUDIO_MIME_TYPE, configuration.mimeType)
        putString(EXTERNAL_AUDIO_LABEL, configuration.label)
    }
    return metadata.buildUpon().setExtras(extras).build()
}

internal fun replaceExternalAudio(
    player: Player?,
    configuration: ExternalAudioConfiguration?,
): Boolean {
    val active = player ?: return false
    val current = active.currentMediaItem ?: return false
    val updatedMetadata = if (configuration == null) {
        val extras = Bundle(current.mediaMetadata.extras ?: Bundle()).apply {
            remove(EXTERNAL_AUDIO_URI)
            remove(EXTERNAL_AUDIO_MIME_TYPE)
            remove(EXTERNAL_AUDIO_LABEL)
        }
        current.mediaMetadata.buildUpon().setExtras(extras).build()
    } else metadataWithExternalAudio(current.mediaMetadata, configuration)
    val updated = current.buildUpon().setMediaMetadata(updatedMetadata).build()
    val position = active.currentPosition.coerceAtLeast(0)
    val resume = active.playWhenReady
    active.setMediaItem(updated, position)
    active.prepare()
    if (resume) active.play() else active.pause()
    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .build()
    return true
}

internal fun externalSubtitleConfiguration(context: Context, uri: Uri): MediaItem.SubtitleConfiguration? {
    if (uri.scheme != "content") return null
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
    val extension = name.substringAfterLast('.', "").lowercase()
    val mimeType = when {
        extension == "vtt" -> MimeTypes.TEXT_VTT
        extension == "srt" -> MimeTypes.APPLICATION_SUBRIP
        context.contentResolver.getType(uri)?.equals(MimeTypes.TEXT_VTT, ignoreCase = true) == true -> MimeTypes.TEXT_VTT
        context.contentResolver.getType(uri)?.contains("subrip", ignoreCase = true) == true -> MimeTypes.APPLICATION_SUBRIP
        else -> return null
    }
    return MediaItem.SubtitleConfiguration.Builder(uri)
        .setMimeType(mimeType)
        .setLanguage(java.util.Locale.getDefault().language.takeIf(String::isNotBlank) ?: "und")
        .setLabel(name.take(256))
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
}

internal fun replaceExternalSubtitle(
    player: Player?,
    subtitle: MediaItem.SubtitleConfiguration?,
): Boolean {
    val active = player ?: return false
    val current = active.currentMediaItem ?: return false
    val local = current.localConfiguration ?: return false
    val existing = local.subtitleConfigurations.filterNot { it.uri.scheme == "content" }
    val configurations = if (subtitle == null) existing else existing + subtitle
    val updated = current.buildUpon().setSubtitleConfigurations(configurations).build()
    val position = active.currentPosition.coerceAtLeast(0)
    val resume = active.playWhenReady
    active.setMediaItem(updated, position)
    active.prepare()
    if (resume) active.play() else active.pause()
    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()
    return true
}

private fun removeFutureMediaItem(player: Player, mediaId: String) {
    if (mediaId.isBlank()) return
    val firstFutureIndex = player.currentMediaItemIndex + 1
    for (index in player.mediaItemCount - 1 downTo firstFutureIndex) {
        if (player.getMediaItemAt(index).mediaId == mediaId) {
            player.removeMediaItems(index, index + 1)
        }
    }
}

private fun applyPlaybackStreamPreferences(
    player: Player,
    preferences: PlaybackStreamPreferences,
) {
    val trackParameters = player.trackSelectionParameters.buildUpon()
        .setMaxVideoSize(Int.MAX_VALUE, preferences.maxHeight)
    if (preferences.fps30Only) trackParameters.setMaxVideoFrameRate(30)
    if (preferences.h264Only) trackParameters.setPreferredVideoMimeType(VIDEO_H264)
    player.trackSelectionParameters = trackParameters.build()
}

private fun trackOptions(tracks: Tracks, type: Int, player: Player, genericTrackLabel: String): List<TrackOption> =
    tracks.groups.flatMap { group ->
        if (group.type != type) return@flatMap emptyList()
        (0 until group.length).mapNotNull { index ->
            if (!group.isTrackSupported(index)) return@mapNotNull null
            val format = group.getTrackFormat(index)
            val language = format.language?.takeUnless { it == "und" }.orEmpty()
            val label = format.label?.takeIf(String::isNotBlank)
                ?: language.takeIf(String::isNotBlank)
                ?: format.codecs?.takeIf(String::isNotBlank)
                ?: "$genericTrackLabel ${index + 1}"
            TrackOption(group.mediaTrackGroup, index, if (group.isTrackSelected(index)) "$label ✓" else label)
        }
    }.distinctBy { it.group to it.index }

private fun updateTrackOverride(player: Player?, type: Int, option: TrackOption?, disable: Boolean = false) {
    player ?: return
    val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
    if (option != null) builder.setOverrideForType(TrackSelectionOverride(option.group, listOf(option.index)))
    if (disable) builder.setTrackTypeDisabled(type, true) else builder.setTrackTypeDisabled(type, false)
    player.trackSelectionParameters = builder.build()
}
