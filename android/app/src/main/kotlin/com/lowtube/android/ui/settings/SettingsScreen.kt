package com.lowtube.android.ui.settings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lowtube.android.AppLocale
import com.lowtube.android.R
import com.lowtube.android.data.PlaybackStreamPreferences
import com.lowtube.android.data.PlaybackBufferPreferences
import com.lowtube.android.data.DownloadStreamPreferences
import com.lowtube.android.data.UiScaleOptions
import com.lowtube.android.data.DiagnosticReportFormatter
import com.lowtube.android.data.DiagnosticReportSnapshot
import com.lowtube.android.data.DownloadRepository
import com.lowtube.android.data.ResourceUsageSample
import com.lowtube.android.data.ResourceUsageSampler
import com.lowtube.android.data.SettingsTransferRepository
import com.lowtube.android.data.LocalLibraryRepository
import com.lowtube.android.data.LocalLibraryTransferRepository
import com.lowtube.android.downloads.DownloadDestination
import com.lowtube.android.playback.PlaybackDecoderDiagnostics
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    onDarkThemeChanged: (Boolean) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onBack: () -> Unit = {},
    discoveryTopic: String,
    discoveryLanguage: String,
    onDiscoveryPreferencesChanged: (String, String) -> Unit,
    feedRefreshPreset: Int = 1,
    onFeedRefreshPresetChanged: (Int) -> Unit = {},
    pageSize: Int = 25,
    onPageSizeChanged: (Int) -> Unit = {},
    playbackPreferences: PlaybackStreamPreferences,
    onPlaybackPreferencesChanged: (PlaybackStreamPreferences) -> Unit,
    playbackBufferPreferences: PlaybackBufferPreferences = PlaybackBufferPreferences(),
    onPlaybackBufferPreferencesChanged: (PlaybackBufferPreferences) -> Unit = {},
    downloadPreferences: DownloadStreamPreferences = DownloadStreamPreferences(),
    onDownloadPreferencesChanged: (DownloadStreamPreferences) -> Unit = {},
    uiScale: Float = UiScaleOptions.DEFAULT,
    onUiScaleChanged: (Float) -> Unit = {},
    onStartFirstRunSetup: () -> Unit = {},
    resumePlayback: Boolean = true,
    onResumePlaybackChanged: (Boolean) -> Unit = {},
    autoRecover: Boolean = true,
    onAutoRecoverChanged: (Boolean) -> Unit = {},
    autoNext: Boolean = true,
    onAutoNextChanged: (Boolean) -> Unit = {},
    preloadNext: Boolean = true,
    onPreloadNextChanged: (Boolean) -> Unit = {},
    downloadRepository: DownloadRepository? = null,
    localLibraryRepository: LocalLibraryRepository? = null,
    onLocalLibraryChanged: () -> Unit = {},
    libraryImport: LibraryImportViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLocale.language(context)) }
    var darkTheme by remember {
        mutableStateOf(context.getSharedPreferences("lowtube_settings", android.content.Context.MODE_PRIVATE).getBoolean("dark_theme", true))
    }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var downloadQualityMenuExpanded by remember { mutableStateOf(false) }
    var downloadAudioBitrateMenuExpanded by remember { mutableStateOf(false) }
    var discoveryLanguageMenuExpanded by remember { mutableStateOf(false) }
    var feedPresetMenuExpanded by remember { mutableStateOf(false) }
    var selectedFeedPreset by remember(feedRefreshPreset) { mutableIntStateOf(feedRefreshPreset.coerceIn(0, 2)) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var resourceSample by remember { mutableStateOf<ResourceUsageSample?>(null) }
    var resourceSampling by remember { mutableStateOf(false) }
    var resourceSampleFailed by remember { mutableStateOf(false) }
    var diagnosticReportCopied by remember { mutableStateOf(false) }
    var diagnosticReportFailed by remember { mutableStateOf(false) }
    var diagnosticReportCopying by remember { mutableStateOf(false) }
    var diagnosticReportPreparing by remember { mutableStateOf(false) }
    var diagnosticReportExported by remember { mutableStateOf(false) }
    var pendingDiagnosticReport by rememberSaveable { mutableStateOf<String?>(null) }
    val diagnosticReportExportPending = diagnosticReportPreparing || pendingDiagnosticReport != null
    var localLibraryTransferMessage by remember { mutableStateOf<String?>(null) }
    var downloadTreeUri by remember(context) { mutableStateOf(DownloadDestination.selectedTreeUri(context)) }
    var downloadFolderError by remember { mutableStateOf(false) }
    val transferScope = rememberCoroutineScope()
    val diagnosticReportExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val report = pendingDiagnosticReport
        pendingDiagnosticReport = null
        if (uri != null && report == null) diagnosticReportFailed = true
        if (uri != null && report != null) transferScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("Could not open the selected diagnostics file.")
                }
            }
            diagnosticReportExported = result.isSuccess
            if (result.isFailure) diagnosticReportFailed = true
        }
    }
    val downloadFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val saved = runCatching {
                DownloadDestination.takeAndSaveTreeUri(context, uri)
            }.isSuccess
            if (saved) {
                downloadTreeUri = uri
                downloadFolderError = false
            } else downloadFolderError = true
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) transferScope.launch {
            val snapshot = SettingsTransferRepository.Snapshot(language, darkTheme, discoveryTopic, discoveryLanguage, playbackPreferences.maxHeight, playbackPreferences.h264Only, playbackPreferences.fps30Only, playbackPreferences.audioOnly, downloadPreferences.maxHeight, downloadPreferences.h264Only, downloadPreferences.fps30Only, downloadPreferences.limitKiB, resumePlayback, playbackBufferPreferences.maximumSeconds, playbackBufferPreferences.memoryLimitMiB, playbackBufferPreferences.backBufferMiB, playbackBufferPreferences.startupSeconds, autoRecover, autoNext, preloadNext, feedRefreshPreset, pageSize, downloadPreferences.audioBitrateKbps, uiScale)
            val result = runCatching {
                val json = SettingsTransferRepository.encode(snapshot)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("Could not open the selected settings file.")
                }
            }
            transferMessage = if (result.isSuccess) context.getString(R.string.settings_exported) else context.getString(R.string.settings_transfer_error)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) transferScope.launch {
            val fallback = SettingsTransferRepository.Snapshot(language, darkTheme, discoveryTopic, discoveryLanguage, playbackPreferences.maxHeight, playbackPreferences.h264Only, playbackPreferences.fps30Only, playbackPreferences.audioOnly, downloadPreferences.maxHeight, downloadPreferences.h264Only, downloadPreferences.fps30Only, downloadPreferences.limitKiB, resumePlayback, playbackBufferPreferences.maximumSeconds, playbackBufferPreferences.memoryLimitMiB, playbackBufferPreferences.backBufferMiB, playbackBufferPreferences.startupSeconds, autoRecover, autoNext, preloadNext, feedRefreshPreset, pageSize, downloadPreferences.audioBitrateKbps, uiScale)
            val result = runCatching {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(::readSettingsTransfer)
                        ?: throw IOException("Could not open the selected settings file.")
                }
                SettingsTransferRepository.decode(json, fallback)
            }
            result.onSuccess { imported ->
                darkTheme = imported.darkTheme
                onDarkThemeChanged(imported.darkTheme)
                onDiscoveryPreferencesChanged(imported.discoveryTopic, imported.discoveryLanguage)
                onPlaybackPreferencesChanged(PlaybackStreamPreferences(imported.maxHeight, imported.h264Only, imported.fps30Only, imported.audioOnly))
                onPlaybackBufferPreferencesChanged(PlaybackBufferPreferences(imported.bufferSeconds, imported.bufferMiB, imported.backBufferMiB, imported.bufferStartSeconds))
                onDownloadPreferencesChanged(DownloadStreamPreferences(imported.downloadMaxHeight, imported.downloadH264Only, imported.downloadFps30Only, imported.downloadLimitKiB, imported.downloadAudioBitrateKbps))
                onUiScaleChanged(imported.uiScale)
                onResumePlaybackChanged(imported.resumePlayback)
                onAutoRecoverChanged(imported.autoRecover)
                onAutoNextChanged(imported.autoNext)
                onPreloadNextChanged(imported.preloadNext)
                selectedFeedPreset = imported.feedPreset
                onFeedRefreshPresetChanged(imported.feedPreset)
                onPageSizeChanged(imported.pageSize)
                language = imported.language
                onLanguageChanged(imported.language)
                transferMessage = context.getString(R.string.settings_imported)
            }.onFailure { transferMessage = context.getString(R.string.settings_transfer_error) }
        }
    }
    val exportLocalLibraryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) transferScope.launch {
            val repository = localLibraryRepository
            val result = runCatching {
                requireNotNull(repository) { "Local library is unavailable." }
                withContext(Dispatchers.IO) {
                    val json = LocalLibraryTransferRepository.encode(repository.exportLocalLibrary())
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_LOCAL_LIBRARY_TRANSFER_BYTES) { "Local library backup is larger than 16 MiB." }
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("Could not open the selected library backup file.")
                }
            }
            localLibraryTransferMessage = if (result.isSuccess) context.getString(R.string.local_library_exported)
            else context.getString(R.string.local_library_transfer_error)
        }
    }
    val importLocalLibraryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) transferScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val json = context.contentResolver.openInputStream(uri)?.use(::readLocalLibraryTransfer)
                        ?: throw IOException("Could not open the selected library backup file.")
                    LocalLibraryTransferRepository.decode(json)
                }
            }
            result.onSuccess { libraryImport.pendingBackup = it }
                .onFailure {
                    if (it is CancellationException) throw it
                    localLibraryTransferMessage = context.getString(R.string.local_library_transfer_error)
                }
        }
    }
    val pendingImport = libraryImport.pendingBackup
    if (pendingImport != null) {
        AlertDialog(
            onDismissRequest = { libraryImport.pendingBackup = null },
            title = { Text(stringResource(R.string.local_library_import_title)) },
            text = { Text(stringResource(R.string.local_library_import_confirmation, pendingImport.channels.size, pendingImport.playlists.size, pendingImport.history.size)) },
            confirmButton = {
                TextButton(onClick = {
                    libraryImport.pendingBackup = null
                    val repository = localLibraryRepository
                    transferScope.launch {
                        val result = runCatching {
                            requireNotNull(repository) { "Local library is unavailable." }
                            withContext(Dispatchers.IO) { repository.replaceLocalLibrary(pendingImport) }
                        }
                        localLibraryTransferMessage = if (result.isSuccess) context.getString(R.string.local_library_imported)
                        else context.getString(R.string.local_library_transfer_error)
                        if (result.isSuccess) onLocalLibraryChanged()
                    }
                }) { Text(stringResource(R.string.local_library_replace)) }
            },
            dismissButton = {
                TextButton(onClick = { libraryImport.pendingBackup = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val availableWidthDp = maxWidth.value * density.density / (configuration.densityDpi / 160f)
        SettingsAdaptiveColumns(
            modifier = if (availableWidthDp >= 800f) {
                Modifier.widthIn(max = 1280.dp).fillMaxWidth().fillMaxHeight()
            } else {
                Modifier.fillMaxSize()
            },
            useTwoColumns = availableWidthDp >= 800f,
            leftContent = {
            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.dark_theme))
                Switch(checked = darkTheme, onCheckedChange = {
                    darkTheme = it
                    onDarkThemeChanged(it)
                })
            }
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            listOf("en" to R.string.language_english, "uk" to R.string.language_ukrainian, "ru" to R.string.language_russian).forEach { (code, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = language == code, onClick = {
                        language = code
                        onLanguageChanged(code)
                    })
                    Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(stringResource(R.string.interface_scale), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            UiScaleOptions.VALUES.forEach { scale ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onUiScaleChanged(scale) },
                ) {
                    RadioButton(selected = uiScale == scale, onClick = { onUiScaleChanged(scale) })
                    Text("${(scale * 100).toInt()}%")
                }
            }
            TextButton(onClick = onStartFirstRunSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_run_again))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.discovery), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = discoveryTopic,
                onValueChange = { onDiscoveryPreferencesChanged(it.take(200), discoveryLanguage) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.discovery_topic)) },
                supportingText = { Text(stringResource(R.string.discovery_topic_hint)) },
                singleLine = true,
            )
            Text(stringResource(R.string.discovery_language), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { discoveryLanguageMenuExpanded = true }) {
                    Text(DISCOVERY_LANGUAGES.firstOrNull { it.first == discoveryLanguage }?.second ?: stringResource(R.string.discovery_language_any))
                }
                DropdownMenu(expanded = discoveryLanguageMenuExpanded, onDismissRequest = { discoveryLanguageMenuExpanded = false }) {
                    DISCOVERY_LANGUAGES.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                discoveryLanguageMenuExpanded = false
                                onDiscoveryPreferencesChanged(discoveryTopic, code)
                            },
                        )
                    }
                }
            }
            NumericPreferenceField(
                value = pageSize,
                label = stringResource(R.string.page_size),
                range = 1..250,
                onValueChanged = onPageSizeChanged,
            )
            Text(stringResource(R.string.page_size_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.subscription_feed_pace), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.subscription_feed_pace_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { feedPresetMenuExpanded = true }) {
                    Text(stringResource(SUBSCRIPTION_FEED_PRESETS[selectedFeedPreset].second))
                }
                DropdownMenu(expanded = feedPresetMenuExpanded, onDismissRequest = { feedPresetMenuExpanded = false }) {
                    SUBSCRIPTION_FEED_PRESETS.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.second)) },
                            onClick = {
                                feedPresetMenuExpanded = false
                                selectedFeedPreset = index
                                onFeedRefreshPresetChanged(index)
                            },
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.playback), style = MaterialTheme.typography.titleMedium)
            PreferenceSwitch(stringResource(R.string.resume_from_last_position), resumePlayback, onResumePlaybackChanged)
            PreferenceSwitch(stringResource(R.string.auto_recover_playback), autoRecover, onAutoRecoverChanged)
            PreferenceSwitch(stringResource(R.string.auto_play_next), autoNext, onAutoNextChanged)
            PreferenceSwitch(stringResource(R.string.preload_next_video), preloadNext, onPreloadNextChanged)
            Text(stringResource(R.string.maximum_quality), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { qualityMenuExpanded = true }) {
                    Text(stringResource(R.string.quality_value, playbackPreferences.maxHeight))
                }
                DropdownMenu(expanded = qualityMenuExpanded, onDismissRequest = { qualityMenuExpanded = false }) {
                    PlaybackStreamPreferences.SUPPORTED_HEIGHTS.sorted().forEach { height ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.quality_value, height)) },
                            onClick = {
                                qualityMenuExpanded = false
                                onPlaybackPreferencesChanged(playbackPreferences.copy(maxHeight = height))
                            },
                        )
                    }
                }
            }
            PreferenceSwitch(stringResource(R.string.h264_only), playbackPreferences.h264Only) {
                onPlaybackPreferencesChanged(playbackPreferences.copy(h264Only = it))
            }
            PreferenceSwitch(stringResource(R.string.fps_30_only), playbackPreferences.fps30Only) {
                onPlaybackPreferencesChanged(playbackPreferences.copy(fps30Only = it))
            }
            PreferenceSwitch(stringResource(R.string.audio_only), playbackPreferences.audioOnly) {
                onPlaybackPreferencesChanged(playbackPreferences.copy(audioOnly = it))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.playback_buffer_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.playback_buffer_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumericPreferenceField(
                value = playbackBufferPreferences.maximumSeconds,
                label = stringResource(R.string.playback_buffer_seconds),
                range = 5..3600,
                onValueChanged = { value ->
                    val startup = minOf(playbackBufferPreferences.startupSeconds, value)
                    onPlaybackBufferPreferencesChanged(playbackBufferPreferences.copy(maximumSeconds = value, startupSeconds = startup))
                },
            )
            NumericPreferenceField(
                value = playbackBufferPreferences.memoryLimitMiB,
                label = stringResource(R.string.playback_buffer_memory_mib),
                range = 16..1024,
                onValueChanged = { onPlaybackBufferPreferencesChanged(playbackBufferPreferences.copy(memoryLimitMiB = it)) },
            )
            NumericPreferenceField(
                value = playbackBufferPreferences.backBufferMiB,
                label = stringResource(R.string.playback_back_buffer_mib),
                range = 0..512,
                onValueChanged = { onPlaybackBufferPreferencesChanged(playbackBufferPreferences.copy(backBufferMiB = it)) },
            )
            NumericPreferenceField(
                value = playbackBufferPreferences.startupSeconds,
                label = stringResource(R.string.playback_start_buffer_seconds),
                range = 1..minOf(30, playbackBufferPreferences.maximumSeconds),
                onValueChanged = { onPlaybackBufferPreferencesChanged(playbackBufferPreferences.copy(startupSeconds = it)) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            },
            rightContent = {
            Text(stringResource(R.string.download_settings), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.maximum_download_quality), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { downloadQualityMenuExpanded = true }) {
                    Text(stringResource(R.string.quality_value, downloadPreferences.maxHeight))
                }
                DropdownMenu(expanded = downloadQualityMenuExpanded, onDismissRequest = { downloadQualityMenuExpanded = false }) {
                    PlaybackStreamPreferences.SUPPORTED_HEIGHTS.sorted().forEach { height ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.quality_value, height)) },
                            onClick = {
                                downloadQualityMenuExpanded = false
                                onDownloadPreferencesChanged(downloadPreferences.copy(maxHeight = height))
                            },
                        )
                    }
                }
            }
            PreferenceSwitch(stringResource(R.string.download_h264_only), downloadPreferences.h264Only) {
                onDownloadPreferencesChanged(downloadPreferences.copy(h264Only = it))
            }
            PreferenceSwitch(stringResource(R.string.download_fps_30_only), downloadPreferences.fps30Only) {
                onDownloadPreferencesChanged(downloadPreferences.copy(fps30Only = it))
            }
            Text(stringResource(R.string.preferred_download_audio_bitrate), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { downloadAudioBitrateMenuExpanded = true }) {
                Text(stringResource(R.string.audio_bitrate_value, downloadPreferences.audioBitrateKbps))
            }
            DropdownMenu(
                expanded = downloadAudioBitrateMenuExpanded,
                onDismissRequest = { downloadAudioBitrateMenuExpanded = false },
            ) {
                DownloadStreamPreferences.SUPPORTED_AUDIO_BITRATES_KBPS.forEach { bitrate ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.audio_bitrate_value, bitrate)) },
                        onClick = {
                            downloadAudioBitrateMenuExpanded = false
                            onDownloadPreferencesChanged(downloadPreferences.copy(audioBitrateKbps = bitrate))
                        },
                    )
                }
            }
            Text(stringResource(R.string.preferred_download_audio_bitrate_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var downloadLimitText by remember(downloadPreferences.limitKiB) { mutableStateOf(if (downloadPreferences.limitKiB == 0) "" else downloadPreferences.limitKiB.toString()) }
            OutlinedTextField(
                value = downloadLimitText,
                onValueChange = { input ->
                    if (input.all(Char::isDigit) && input.length <= 7) {
                        downloadLimitText = input
                        val parsed = input.toIntOrNull()
                        if (input.isEmpty() || parsed in 0..1_048_576) onDownloadPreferencesChanged(downloadPreferences.copy(limitKiB = parsed ?: 0))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.download_speed_limit)) },
                supportingText = { Text(stringResource(R.string.download_speed_limit_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
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
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.settings_transfer), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text(stringResource(R.string.import_settings))
                }
                TextButton(onClick = { exportLauncher.launch("lowtube-settings.json") }) {
                    Text(stringResource(R.string.export_settings))
                }
            }
            transferMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.local_library_transfer), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.local_library_transfer_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = localLibraryRepository != null,
                    onClick = { exportLocalLibraryLauncher.launch("lowtube-library.json") },
                ) { Text(stringResource(R.string.export_local_library)) }
                TextButton(
                    enabled = localLibraryRepository != null,
                    onClick = { importLocalLibraryLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                ) { Text(stringResource(R.string.import_local_library)) }
            }
            localLibraryTransferMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.resource_usage_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.resource_usage_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                enabled = !resourceSampling,
                onClick = {
                    resourceSampling = true
                    resourceSampleFailed = false
                    transferScope.launch {
                        try {
                            resourceSample = ResourceUsageSampler.capture(context.applicationContext)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            resourceSampleFailed = true
                        } finally {
                            resourceSampling = false
                        }
                    }
                },
            ) {
                Text(stringResource(if (resourceSampling) R.string.resource_usage_sampling else R.string.resource_usage_measure))
            }
            if (resourceSampleFailed) Text(stringResource(R.string.resource_usage_error), color = MaterialTheme.colorScheme.error)
            TextButton(enabled = !diagnosticReportCopying, onClick = {
                diagnosticReportCopied = false
                diagnosticReportFailed = false
                diagnosticReportCopying = true
                transferScope.launch {
                    try {
                        val report = createDiagnosticReport(
                            context = context.applicationContext,
                            playbackBufferPreferences = playbackBufferPreferences,
                            resourceSample = resourceSample,
                            downloadRepository = downloadRepository,
                        )
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.diagnostic_report_clip_label), report))
                        diagnosticReportCopied = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        diagnosticReportFailed = true
                    } finally {
                        diagnosticReportCopying = false
                    }
                }
            }) { Text(stringResource(if (diagnosticReportCopying) R.string.diagnostic_report_copying else R.string.copy_diagnostic_report)) }
            TextButton(enabled = !diagnosticReportExportPending, onClick = {
                diagnosticReportExported = false
                diagnosticReportFailed = false
                diagnosticReportPreparing = true
                transferScope.launch {
                    try {
                        pendingDiagnosticReport = createDiagnosticReport(
                            context = context.applicationContext,
                            playbackBufferPreferences = playbackBufferPreferences,
                            resourceSample = resourceSample,
                            downloadRepository = downloadRepository,
                        )
                        diagnosticReportExportLauncher.launch("lowtube-diagnostics.txt")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        pendingDiagnosticReport = null
                        diagnosticReportFailed = true
                    } finally {
                        diagnosticReportPreparing = false
                    }
                }
            }) {
                Text(stringResource(if (diagnosticReportExportPending) R.string.diagnostic_report_exporting else R.string.export_diagnostic_report))
            }
            if (diagnosticReportCopied) Text(stringResource(R.string.diagnostic_report_copied), style = MaterialTheme.typography.bodySmall)
            if (diagnosticReportExported) Text(stringResource(R.string.diagnostic_report_exported), style = MaterialTheme.typography.bodySmall)
            if (diagnosticReportFailed) Text(stringResource(R.string.diagnostic_report_error), color = MaterialTheme.colorScheme.error)
            resourceSample?.let { sample ->
                Column(
                    modifier = Modifier.testTag("resource_usage_sample_result"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.resource_usage_last_sample, sample.durationMs / 1000.0), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.resource_usage_cpu, sample.cpuPercentOneCore))
                    Text(stringResource(R.string.resource_usage_pss, sample.pssStartKiB / 1024.0, sample.pssEndKiB / 1024.0))
                    val rssStart = sample.rssStartKiB
                    val rssEnd = sample.rssEndKiB
                    if (rssStart != null && rssEnd != null) {
                        Text(stringResource(R.string.resource_usage_rss, rssStart / 1024.0, rssEnd / 1024.0))
                    } else {
                        Text(stringResource(R.string.resource_usage_rss_unavailable))
                    }
                    val peakRss = sample.peakRssKiB
                    if (peakRss != null) Text(stringResource(R.string.resource_usage_peak_rss, peakRss / 1024.0))
                    Text(stringResource(R.string.resource_usage_system_ram, sample.availableRamStartMiB, sample.availableRamEndMiB, sample.totalRamMiB))
                }
            }
            },
        )
        }
    }
}

@Composable
private fun SettingsAdaptiveColumns(
    modifier: Modifier,
    useTwoColumns: Boolean,
    leftContent: @Composable ColumnScope.() -> Unit,
    rightContent: @Composable ColumnScope.() -> Unit,
) {
    if (useTwoColumns) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)
                    .testTag(SETTINGS_LEFT_PANE_TEST_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = leftContent,
            )
            VerticalDivider(Modifier.fillMaxHeight())
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)
                    .testTag(SETTINGS_RIGHT_PANE_TEST_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = rightContent,
            )
        }
    } else {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag(SETTINGS_SINGLE_COLUMN_TEST_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leftContent()
            rightContent()
        }
    }
}

internal const val SETTINGS_LEFT_PANE_TEST_TAG = "lowtube-settings-left-pane"
internal const val SETTINGS_RIGHT_PANE_TEST_TAG = "lowtube-settings-right-pane"
internal const val SETTINGS_SINGLE_COLUMN_TEST_TAG = "lowtube-settings-single-column"

private suspend fun createDiagnosticReport(
    context: Context,
    playbackBufferPreferences: PlaybackBufferPreferences,
    resourceSample: ResourceUsageSample?,
    downloadRepository: DownloadRepository?,
): String {
    val packageInfo = withContext(Dispatchers.IO) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    val decoderPreferences = context.getSharedPreferences(PlaybackDecoderDiagnostics.PREFERENCES_NAME, Context.MODE_PRIVATE)
    val downloadJobs = try {
        downloadRepository?.let { repository -> withContext(Dispatchers.IO) { repository.diagnosticStatusCounts() } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    return DiagnosticReportFormatter.format(
        DiagnosticReportSnapshot(
            appVersion = "${packageInfo.versionName.orEmpty()} ($versionCode)",
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            androidApi = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim(),
            decoder = PlaybackDecoderDiagnostics.read(decoderPreferences),
            buffer = playbackBufferPreferences,
            resourceSample = resourceSample,
            downloadJobs = downloadJobs,
            playbackFailure = PlaybackDecoderDiagnostics.readFailure(decoderPreferences),
        ),
    )
}

private val DISCOVERY_LANGUAGES = listOf(
    "any" to "Any language",
    "en" to "English",
    "uk" to "Українська",
    "ru" to "Русский",
    "pl" to "Polski",
    "de" to "Deutsch",
    "fr" to "Français",
    "es" to "Español",
    "it" to "Italiano",
    "pt" to "Português",
    "ja" to "日本語",
    "ko" to "한국어",
)

private val SUBSCRIPTION_FEED_PRESETS = listOf(
    0 to R.string.connection_preset_slow,
    1 to R.string.connection_preset_balanced,
    2 to R.string.connection_preset_fast,
)

@Composable
private fun NumericPreferenceField(
    value: Int,
    label: String,
    range: IntRange,
    onValueChanged: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= 4 && input.all(Char::isDigit)) {
                text = input
                input.toIntOrNull()?.takeIf { it in range }?.let(onValueChanged)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun readSettingsTransfer(input: java.io.InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > 64 * 1024) throw IOException("Settings file is larger than 64 KiB.")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun readLocalLibraryTransfer(input: java.io.InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_LOCAL_LIBRARY_TRANSFER_BYTES) throw IOException("Local library backup is larger than 16 MiB.")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

private const val MAX_LOCAL_LIBRARY_TRANSFER_BYTES = 16 * 1024 * 1024
