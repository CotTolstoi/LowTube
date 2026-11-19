package com.lowtube.android.data

import android.content.Context
import android.net.Uri

/** Checks the persistable SAF read grant and opens the document before starting playback. */
internal object LocalAudioAvailability {
    fun canRead(context: Context, uri: String): Boolean {
        val document = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        if (document.scheme != "content" || document.authority.isNullOrBlank()) return false
        val resolver = context.applicationContext.contentResolver
        val hasPersistedReadGrant = runCatching {
            resolver.persistedUriPermissions.any { it.uri == document && it.isReadPermission }
        }.getOrDefault(false)
        if (!hasPersistedReadGrant) return false
        return runCatching { resolver.openFileDescriptor(document, "r")?.use { true } ?: false }
            .getOrDefault(false)
    }
}
