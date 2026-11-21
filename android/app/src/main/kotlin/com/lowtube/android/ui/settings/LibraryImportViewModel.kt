package com.lowtube.android.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lowtube.android.data.LocalLibraryTransferRepository

internal class LibraryImportViewModel : ViewModel() {
    // A validated backup can be too large for the saved-instance-state Bundle.
    var pendingBackup by mutableStateOf<LocalLibraryTransferRepository.Snapshot?>(null)

    var reading by mutableStateOf(false)
        private set
    var readFailed by mutableStateOf(false)
        private set
    private var readJob: Job? = null

    fun loadBackup(resolver: ContentResolver, uri: Uri) {
        clear()
        reading = true
        readJob = viewModelScope.launch {
            try {
                pendingBackup = withContext(Dispatchers.IO) {
                    val json = resolver.openInputStream(uri)?.use(::readLocalLibraryTransfer)
                        ?: throw IOException("Could not open the selected library backup file.")
                    LocalLibraryTransferRepository.decode(json)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                readFailed = true
            }
            reading = false
        }
    }

    fun clear() {
        readJob?.cancel()
        readJob = null
        pendingBackup = null
        reading = false
        readFailed = false
    }
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

internal const val MAX_LOCAL_LIBRARY_TRANSFER_BYTES = 16 * 1024 * 1024
