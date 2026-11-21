package com.lowtube.android.ui.downloads

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lowtube.android.R
import com.lowtube.android.data.DownloadRepository
import com.lowtube.android.data.DownloadStreamPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class DownloadRetryViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableRetryingIds = MutableStateFlow<Set<Long>>(emptySet())
    val retryingIds = mutableRetryingIds.asStateFlow()

    fun retry(repository: DownloadRepository, id: Long, preferences: DownloadStreamPreferences) {
        if (id in mutableRetryingIds.value) return
        mutableRetryingIds.update { it + id }
        viewModelScope.launch {
            try {
                repository.retry(id, preferences)
                Toast.makeText(getApplication(), R.string.download_started, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(getApplication(), R.string.download_failed, Toast.LENGTH_LONG).show()
            } finally {
                mutableRetryingIds.update { it - id }
            }
        }
    }
}
