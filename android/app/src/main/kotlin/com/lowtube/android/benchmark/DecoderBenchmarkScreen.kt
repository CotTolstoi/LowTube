package com.lowtube.android.benchmark

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.lowtube.android.R
import com.lowtube.android.playback.PlaybackDecoderDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DecoderBenchmarkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Uri?>(null) }
    var profile by rememberSaveable { mutableStateOf(AndroidDecoderProfile.DEFAULT) }
    var durationSeconds by rememberSaveable { mutableLongStateOf(30L) }
    var activePlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var progressMs by remember { mutableLongStateOf(0L) }
    var progressTotalMs by remember { mutableLongStateOf(0L) }
    var failureMessage by remember { mutableStateOf("") }
    var hasHardwareCodecForSource by remember { mutableStateOf<Boolean?>(null) }
    var comparisonIndex by remember { mutableStateOf(0) }
    var comparisonCount by remember { mutableStateOf(0) }
    var activeBenchmarkProfile by remember { mutableStateOf(AndroidDecoderProfile.DEFAULT) }
    val results = remember { mutableStateListOf<AndroidDecoderBenchmarkResult>() }

    LaunchedEffect(source) {
        hasHardwareCodecForSource = null
        val selectedSource = source ?: return@LaunchedEffect
        if (DecoderBenchmarkEngine.isHardwareProfileAvailable()) {
            hasHardwareCodecForSource = withContext(Dispatchers.IO) {
                DecoderBenchmarkEngine.hasHardwareVideoDecoder(context, selectedSource)
            }
        }
    }

    val openVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            source = uri
            failureMessage = ""
            results.clear()
            progressMs = 0L
        }
    }
    val exportReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { it.write(benchmarkReport(results)) }
                } ?: throw IllegalStateException("Could not open the report file.")
            }.onFailure { failureMessage = context.getString(R.string.decoder_benchmark_export_failed) }
        }
    }

    val currentRunJob by rememberUpdatedState(runJob)
    DisposableEffect(Unit) { onDispose { currentRunJob?.cancel() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.decoder_benchmark)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.decoder_benchmark_description), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.decoder_benchmark_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedButton(onClick = { openVideo.launch(arrayOf("video/*")) }, enabled = runJob == null) {
                Text(stringResource(if (source == null) R.string.decoder_benchmark_select_video else R.string.decoder_benchmark_change_video))
            }
            if (source != null) Text(stringResource(R.string.decoder_benchmark_source_selected), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.decoder_benchmark_profile), style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AndroidDecoderProfile.entries.forEach { candidate ->
                    val hardwareUnavailable = candidate == AndroidDecoderProfile.HARDWARE_ONLY &&
                        (!DecoderBenchmarkEngine.isHardwareProfileAvailable() || hasHardwareCodecForSource == false)
                    FilterChip(
                        selected = profile == candidate,
                        onClick = { profile = candidate },
                        enabled = runJob == null && !hardwareUnavailable,
                        label = { Text(profileLabel(candidate)) },
                    )
                }
            }
            if (!DecoderBenchmarkEngine.isHardwareProfileAvailable()) {
                Text(stringResource(R.string.decoder_benchmark_hardware_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (source != null && hasHardwareCodecForSource == false) {
                Text(stringResource(R.string.decoder_benchmark_hardware_no_codec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (profile == AndroidDecoderProfile.SOFTWARE_PREFERRED) {
                Text(stringResource(R.string.decoder_benchmark_software_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(stringResource(R.string.decoder_benchmark_duration), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15L, 30L, 60L, 120L).forEach { seconds ->
                    FilterChip(
                        selected = durationSeconds == seconds,
                        onClick = { durationSeconds = seconds },
                        enabled = runJob == null,
                        label = { Text(stringResource(R.string.duration_seconds_short, seconds)) },
                    )
                }
            }
            Text(stringResource(R.string.decoder_benchmark_two_minute_cap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Button(
                onClick = {
                    val selectedSource = source ?: return@Button
                    failureMessage = ""
                    progressMs = 0L
                    progressTotalMs = durationSeconds * 1_000L
                    activeBenchmarkProfile = profile
                    comparisonIndex = 0
                    comparisonCount = 0
                    val player = runCatching { DecoderBenchmarkEngine.createPlayer(context, profile) }
                        .getOrElse {
                            failureMessage = context.getString(R.string.decoder_benchmark_profile_unavailable)
                            return@Button
                        }
                    activePlayer?.release()
                    activePlayer = player
                    val selectedProfile = profile
                    val selectedDuration = durationSeconds.toInt()
                    runJob = scope.launch {
                        try {
                            val result = DecoderBenchmarkEngine.run(
                                context = context,
                                player = player,
                                uri = selectedSource,
                                profile = selectedProfile,
                                requestedSeconds = selectedDuration,
                            ) { current, total ->
                                progressMs = current
                                progressTotalMs = total
                            }
                            if (results.size >= 10) results.removeAt(0)
                            results.add(result)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failureMessage = context.getString(R.string.decoder_benchmark_run_failed)
                        } finally {
                            player.release()
                            if (activePlayer === player) activePlayer = null
                            runJob = null
                        }
                    }
                },
                enabled = source != null && runJob == null && (profile != AndroidDecoderProfile.HARDWARE_ONLY || (DecoderBenchmarkEngine.isHardwareProfileAvailable() && hasHardwareCodecForSource == true)),
            ) { Text(stringResource(R.string.decoder_benchmark_run)) }
            OutlinedButton(
                onClick = {
                    val selectedSource = source ?: return@OutlinedButton
                    val profiles = buildList {
                        add(AndroidDecoderProfile.DEFAULT)
                        add(AndroidDecoderProfile.SOFTWARE_PREFERRED)
                        if (DecoderBenchmarkEngine.isHardwareProfileAvailable() && hasHardwareCodecForSource == true) {
                            add(AndroidDecoderProfile.HARDWARE_ONLY)
                        }
                    }
                    val selectedDuration = durationSeconds.toInt().coerceIn(15, 120)
                    failureMessage = ""
                    comparisonIndex = 0
                    comparisonCount = profiles.size
                    runJob = scope.launch {
                        try {
                            profiles.forEachIndexed { index, candidate ->
                                comparisonIndex = index + 1
                                activeBenchmarkProfile = candidate
                                progressMs = 0L
                                progressTotalMs = selectedDuration * 1_000L
                                val player = try {
                                    DecoderBenchmarkEngine.createPlayer(context, candidate)
                                } catch (_: Exception) {
                                    failureMessage = context.getString(R.string.decoder_benchmark_profile_unavailable)
                                    return@forEachIndexed
                                }
                                activePlayer = player
                                try {
                                    val result = DecoderBenchmarkEngine.run(
                                        context = context,
                                        player = player,
                                        uri = selectedSource,
                                        profile = candidate,
                                        requestedSeconds = selectedDuration,
                                    ) { current, total ->
                                        progressMs = current
                                        progressTotalMs = total
                                    }
                                    if (results.size >= 10) results.removeAt(0)
                                    results.add(result)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    failureMessage = context.getString(R.string.decoder_benchmark_run_failed)
                                } finally {
                                    player.release()
                                    if (activePlayer === player) activePlayer = null
                                }
                            }
                        } finally {
                            comparisonIndex = 0
                            comparisonCount = 0
                            activePlayer?.release()
                            activePlayer = null
                            runJob = null
                        }
                    }
                },
                enabled = source != null && runJob == null &&
                    (!DecoderBenchmarkEngine.isHardwareProfileAvailable() || hasHardwareCodecForSource != null),
            ) { Text(stringResource(R.string.decoder_benchmark_compare)) }
            if (runJob != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.decoder_benchmark_running, progressMs / 1000, progressTotalMs / 1000))
                }
                if (comparisonCount > 0) {
                    Text(
                        stringResource(
                            R.string.decoder_benchmark_comparison_step,
                            comparisonIndex,
                            comparisonCount,
                            profileLabel(activeBenchmarkProfile),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { runJob?.cancel() }) { Text(stringResource(R.string.cancel)) }
                activePlayer?.let { player ->
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        factory = { viewContext -> PlayerView(viewContext).apply { useController = true; this.player = player } },
                        update = { it.player = player },
                    )
                }
            }
            if (failureMessage.isNotBlank()) Text(failureMessage, color = MaterialTheme.colorScheme.error)

            if (results.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.decoder_benchmark_results), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { exportReport.launch("lowtube-decoder-benchmark.txt") }) {
                        Text(stringResource(R.string.decoder_benchmark_export))
                    }
                }
                results.asReversed().forEach { result ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${profileLabel(result.profile)} · ${statusLabel(result.status)}", style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.decoder_benchmark_decoder, result.decoderName.ifBlank { "—" }, hardwareLabel(result.hardwareStatus)), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.decoder_benchmark_summary, result.cpuMeanPercentOneCore, result.pssPeakMiB, result.processedFrames, result.droppedFrames, result.mediaAdvanceSeconds, result.wallSeconds), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.decoder_benchmark_av_sync, stringResource(R.string.decoder_benchmark_av_sync_unavailable)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun profileLabel(profile: AndroidDecoderProfile): String = when (profile) {
    AndroidDecoderProfile.DEFAULT -> stringResource(R.string.decoder_benchmark_default_profile)
    AndroidDecoderProfile.SOFTWARE_PREFERRED -> stringResource(R.string.decoder_benchmark_software_profile)
    AndroidDecoderProfile.HARDWARE_ONLY -> stringResource(R.string.decoder_benchmark_hardware_profile)
}

@Composable
private fun hardwareLabel(status: Int): String = when (status) {
    PlaybackDecoderDiagnostics.HARDWARE_STATUS_HARDWARE -> stringResource(R.string.video_hardware_yes)
    PlaybackDecoderDiagnostics.HARDWARE_STATUS_SOFTWARE -> stringResource(R.string.video_hardware_no)
    else -> stringResource(R.string.video_hardware_unknown)
}

@Composable
private fun statusLabel(status: String): String = stringResource(when (status) {
    "OK" -> R.string.decoder_benchmark_status_ok
    "FRAME_DROPS" -> R.string.decoder_benchmark_status_frame_drops
    "SLOW_OR_STALLED" -> R.string.decoder_benchmark_status_slow
    "UNVERIFIED_FPS" -> R.string.decoder_benchmark_status_fps
    "SOURCE_ENDED_EARLY" -> R.string.decoder_benchmark_status_early_end
    "WATCHDOG_TIMEOUT" -> R.string.decoder_benchmark_status_timeout
    else -> R.string.decoder_benchmark_status_insufficient
})

internal fun benchmarkReport(results: List<AndroidDecoderBenchmarkResult>): String = buildString {
    appendLine("LowTube Android decoder benchmark")
    appendLine("Methodology: same user-selected local video URI, playback from media time 0, maximum clip endpoint 120 s, default 2 s warm-up, separate profile runs.")
    appendLine("Privacy: source URI, path, filename, video title and account data are excluded.")
    appendLine("A/V sync: raw audio/video decoder PTS skew is not exposed by this Media3 runner; reported as unavailable.")
    appendLine()
    results.forEachIndexed { index, result ->
        appendLine("Run ${index + 1}: ${result.asReportLine()}")
    }
}
