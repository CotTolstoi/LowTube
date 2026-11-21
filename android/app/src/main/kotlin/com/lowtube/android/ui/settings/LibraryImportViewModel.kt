package com.lowtube.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.lowtube.android.data.LocalLibraryTransferRepository

internal class LibraryImportViewModel : ViewModel() {
    // A validated backup can be too large for the saved-instance-state Bundle.
    var pendingBackup by mutableStateOf<LocalLibraryTransferRepository.Snapshot?>(null)
}
