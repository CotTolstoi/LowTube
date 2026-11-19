package com.lowtube.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lowtube.android.R
import com.lowtube.android.data.PlaybackStreamPreferences
import com.lowtube.android.data.DownloadStreamPreferences
import com.lowtube.android.data.UiScaleOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FirstRunSetupScreen(
    language: String,
    uiScale: Float,
    darkTheme: Boolean,
    playbackMaxHeight: Int,
    downloadAudioBitrateKbps: Int,
    onPreviewUiScale: (Float) -> Unit,
    onApply: (language: String, uiScale: Float, darkTheme: Boolean, playbackHeight: Int, audioBitrateKbps: Int) -> Unit,
    onSkip: () -> Unit,
) {
    var selectedLanguage by remember(language) { mutableStateOf(language) }
    var selectedScale by remember(uiScale) { mutableStateOf(UiScaleOptions.normalize(uiScale)) }
    var selectedDarkTheme by remember(darkTheme) { mutableStateOf(darkTheme) }
    var selectedHeight by remember(playbackMaxHeight) { mutableStateOf(playbackMaxHeight) }
    var selectedBitrate by remember(downloadAudioBitrateKbps) { mutableStateOf(downloadAudioBitrateKbps) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var bitrateExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

            Text(stringResource(R.string.maximum_quality), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { qualityExpanded = true }) { Text(stringResource(R.string.quality_value, selectedHeight)) }
            DropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) {
                PlaybackStreamPreferences.SUPPORTED_HEIGHTS.sorted().forEach { height ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.quality_value, height)) },
                        onClick = { selectedHeight = height; qualityExpanded = false },
                    )
                }
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

            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().testTag(FIRST_RUN_SKIP_TAG)) {
                Text(stringResource(R.string.onboarding_skip))
            }
            Button(
                onClick = { onApply(selectedLanguage, selectedScale, selectedDarkTheme, selectedHeight, selectedBitrate) },
                modifier = Modifier.fillMaxWidth().testTag(FIRST_RUN_FINISH_TAG),
            ) {
                Text(stringResource(R.string.onboarding_finish))
            }
        }
    }
}

internal const val FIRST_RUN_FINISH_TAG = "lowtube-first-run-finish"
internal const val FIRST_RUN_SKIP_TAG = "lowtube-first-run-skip"
