package com.lowtube.android.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lowtube.android.R
import com.lowtube.android.data.PlaybackStreamPreferences
import com.lowtube.android.data.DownloadStreamPreferences
import com.lowtube.android.data.FirstRunSetupSelection
import com.lowtube.android.data.UiScaleOptions
import com.lowtube.android.downloads.DownloadDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FirstRunSetupScreen(
    language: String,
    uiScale: Float,
    darkTheme: Boolean,
    playbackPreferences: PlaybackStreamPreferences,
    downloadPreferences: DownloadStreamPreferences,
    onPreviewUiScale: (Float) -> Unit,
    onApply: (FirstRunSetupSelection) -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    var selectedLanguage by remember(language) { mutableStateOf(language) }
    var selectedScale by remember(uiScale) { mutableStateOf(UiScaleOptions.normalize(uiScale)) }
    var selectedDarkTheme by remember(darkTheme) { mutableStateOf(darkTheme) }
    var selectedPlaybackHeight by remember(playbackPreferences.maxHeight) { mutableStateOf(playbackPreferences.maxHeight) }
    var selectedPlaybackH264 by remember(playbackPreferences.h264Only) { mutableStateOf(playbackPreferences.h264Only) }
    var selectedPlaybackFps30 by remember(playbackPreferences.fps30Only) { mutableStateOf(playbackPreferences.fps30Only) }
    var selectedPlaybackAudioOnly by remember(playbackPreferences.audioOnly) { mutableStateOf(playbackPreferences.audioOnly) }
    var selectedDownloadHeight by remember(downloadPreferences.maxHeight) { mutableStateOf(downloadPreferences.maxHeight) }
    var selectedDownloadH264 by remember(downloadPreferences.h264Only) { mutableStateOf(downloadPreferences.h264Only) }
    var selectedDownloadFps30 by remember(downloadPreferences.fps30Only) { mutableStateOf(downloadPreferences.fps30Only) }
    var selectedBitrate by remember(downloadPreferences.audioBitrateKbps) { mutableStateOf(downloadPreferences.audioBitrateKbps) }
    var playbackQualityExpanded by remember { mutableStateOf(false) }
    var downloadQualityExpanded by remember { mutableStateOf(false) }
    var bitrateExpanded by remember { mutableStateOf(false) }
    var downloadTreeUri by remember(context) { mutableStateOf(DownloadDestination.selectedTreeUri(context)) }
    var downloadFolderError by remember { mutableStateOf(false) }
    val downloadFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val saved = runCatching { DownloadDestination.takeAndSaveTreeUri(context, uri) }.isSuccess
            if (saved) {
                downloadTreeUri = uri
                downloadFolderError = false
            } else downloadFolderError = true
        }
    }

    val appearanceAndLanguage: @Composable ColumnScope.() -> Unit = {
        Text(stringResource(R.string.onboarding_description), style = MaterialTheme.typography.bodyLarge)

        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
        listOf("en" to R.string.language_english, "uk" to R.string.language_ukrainian, "ru" to R.string.language_russian).forEach { (code, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { selectedLanguage = code },
            ) {
                RadioButton(selected = selectedLanguage == code, onClick = { selectedLanguage = code })
                Text(stringResource(label))
            }
        }

        Text(stringResource(R.string.interface_scale), style = MaterialTheme.typography.titleMedium)
        UiScaleOptions.VALUES.forEach { scale ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { selectedScale = scale; onPreviewUiScale(scale) },
            ) {
                RadioButton(
                    selected = selectedScale == scale,
                    onClick = { selectedScale = scale; onPreviewUiScale(scale) },
                )
                Text("${(scale * 100).toInt()}%")
            }
        }

        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.dark_theme))
            Switch(checked = selectedDarkTheme, onCheckedChange = { selectedDarkTheme = it })
        }
    }

    val playbackAndDownloads: @Composable ColumnScope.() -> Unit = {
        Text(stringResource(R.string.playback), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.maximum_quality), style = MaterialTheme.typography.titleMedium)
        TextButton(
            onClick = { playbackQualityExpanded = true },
            modifier = Modifier.testTag(FIRST_RUN_PLAYBACK_QUALITY_TAG),
        ) { Text(stringResource(R.string.quality_value, selectedPlaybackHeight)) }
        DropdownMenu(expanded = playbackQualityExpanded, onDismissRequest = { playbackQualityExpanded = false }) {
            PlaybackStreamPreferences.SUPPORTED_HEIGHTS.sorted().forEach { height ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.quality_value, height)) },
                    onClick = { selectedPlaybackHeight = height; playbackQualityExpanded = false },
                )
            }
        }
        SetupPreferenceSwitch(stringResource(R.string.h264_only), selectedPlaybackH264, FIRST_RUN_PLAYBACK_H264_TAG) {
            selectedPlaybackH264 = it
        }
        SetupPreferenceSwitch(stringResource(R.string.fps_30_only), selectedPlaybackFps30, FIRST_RUN_PLAYBACK_FPS30_TAG) {
            selectedPlaybackFps30 = it
        }
        SetupPreferenceSwitch(stringResource(R.string.audio_only), selectedPlaybackAudioOnly, FIRST_RUN_PLAYBACK_AUDIO_ONLY_TAG) {
            selectedPlaybackAudioOnly = it
        }

        Text(stringResource(R.string.download_settings), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.maximum_download_quality), style = MaterialTheme.typography.titleMedium)
        TextButton(
            onClick = { downloadQualityExpanded = true },
            modifier = Modifier.testTag(FIRST_RUN_DOWNLOAD_QUALITY_TAG),
        ) { Text(stringResource(R.string.quality_value, selectedDownloadHeight)) }
        DropdownMenu(expanded = downloadQualityExpanded, onDismissRequest = { downloadQualityExpanded = false }) {
            PlaybackStreamPreferences.SUPPORTED_HEIGHTS.sorted().forEach { height ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.quality_value, height)) },
                    onClick = { selectedDownloadHeight = height; downloadQualityExpanded = false },
                )
            }
        }
        SetupPreferenceSwitch(stringResource(R.string.download_h264_only), selectedDownloadH264, FIRST_RUN_DOWNLOAD_H264_TAG) {
            selectedDownloadH264 = it
        }
        SetupPreferenceSwitch(stringResource(R.string.download_fps_30_only), selectedDownloadFps30, FIRST_RUN_DOWNLOAD_FPS30_TAG) {
            selectedDownloadFps30 = it
        }
        Text(stringResource(R.string.preferred_download_audio_bitrate), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { bitrateExpanded = true }) { Text(stringResource(R.string.audio_bitrate_value, selectedBitrate)) }
        DropdownMenu(expanded = bitrateExpanded, onDismissRequest = { bitrateExpanded = false }) {
            DownloadStreamPreferences.SUPPORTED_AUDIO_BITRATES_KBPS.forEach { bitrate ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_bitrate_value, bitrate)) },
                    onClick = { selectedBitrate = bitrate; bitrateExpanded = false },
                )
            }
        }

        Text(stringResource(R.string.download_location_title), style = MaterialTheme.typography.titleMedium)
        val selectedFolderName = downloadTreeUri?.let { DownloadDestination.displayName(context, it) }
        Text(
            if (downloadTreeUri == null) stringResource(R.string.download_location_default)
            else stringResource(R.string.download_location_selected, selectedFolderName ?: stringResource(R.string.download_location_custom)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { downloadFolderPicker.launch(null) }) {
                Text(stringResource(R.string.choose_download_folder))
            }
            if (downloadTreeUri != null) {
                TextButton(onClick = {
                    val cleared = runCatching { DownloadDestination.clearTreeUri(context) }.isSuccess
                    if (cleared) {
                        downloadTreeUri = null
                        downloadFolderError = false
                    } else downloadFolderError = true
                }) {
                    Text(stringResource(R.string.use_default_download_folder))
                }
            }
        }
        if (downloadFolderError) Text(stringResource(R.string.download_location_error), color = MaterialTheme.colorScheme.error)
    }

    val actions: @Composable RowScope.() -> Unit = {
        TextButton(onClick = onSkip, modifier = Modifier.testTag(FIRST_RUN_SKIP_TAG)) {
            Text(stringResource(R.string.onboarding_skip))
        }
        Button(
            onClick = {
                onApply(
                    FirstRunSetupSelection(
                        language = selectedLanguage,
                        uiScale = selectedScale,
                        darkTheme = selectedDarkTheme,
                        playback = PlaybackStreamPreferences(
                            maxHeight = selectedPlaybackHeight,
                            h264Only = selectedPlaybackH264,
                            fps30Only = selectedPlaybackFps30,
                            audioOnly = selectedPlaybackAudioOnly,
                        ),
                        downloads = DownloadStreamPreferences(
                            maxHeight = selectedDownloadHeight,
                            h264Only = selectedDownloadH264,
                            fps30Only = selectedDownloadFps30,
                            audioBitrateKbps = selectedBitrate,
                        ),
                    ),
                )
            },
            modifier = Modifier.testTag(FIRST_RUN_FINISH_TAG),
        ) {
            Text(stringResource(R.string.onboarding_finish))
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            val availableWidthDp = maxWidth.value * density.density / (configuration.densityDpi / 160f)
            if (availableWidthDp >= FIRST_RUN_WIDE_LAYOUT_MIN_DP) {
                Column(
                    Modifier.widthIn(max = 1280.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(
                            Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { appearanceAndLanguage() }
                        VerticalDivider(Modifier.fillMaxHeight())
                        Column(
                            Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { playbackAndDownloads() }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).clipToBounds(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        appearanceAndLanguage()
                        playbackAndDownloads()
                    }
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupPreferenceSwitch(
    label: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(testTag))
    }
}

private const val FIRST_RUN_WIDE_LAYOUT_MIN_DP = 800f

internal const val FIRST_RUN_FINISH_TAG = "lowtube-first-run-finish"
internal const val FIRST_RUN_SKIP_TAG = "lowtube-first-run-skip"
internal const val FIRST_RUN_PLAYBACK_QUALITY_TAG = "lowtube-first-run-playback-quality"
internal const val FIRST_RUN_PLAYBACK_H264_TAG = "lowtube-first-run-playback-h264"
internal const val FIRST_RUN_PLAYBACK_FPS30_TAG = "lowtube-first-run-playback-fps30"
internal const val FIRST_RUN_PLAYBACK_AUDIO_ONLY_TAG = "lowtube-first-run-playback-audio-only"
internal const val FIRST_RUN_DOWNLOAD_QUALITY_TAG = "lowtube-first-run-download-quality"
internal const val FIRST_RUN_DOWNLOAD_H264_TAG = "lowtube-first-run-download-h264"
internal const val FIRST_RUN_DOWNLOAD_FPS30_TAG = "lowtube-first-run-download-fps30"
