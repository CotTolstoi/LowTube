package com.lowtube.android.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Device-local SAF destination. It is intentionally excluded from PC settings transfer. */
internal object DownloadDestination {
    private const val PREFERENCES = "lowtube_settings"
    private const val TREE_URI_KEY = "download_tree_uri"

    fun selectedTreeUri(context: Context): Uri? = runCatching {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(TREE_URI_KEY, null)
            ?.let(Uri::parse)
            ?.takeIf(DocumentsContract::isTreeUri)
    }.getOrNull()

    fun takeAndSaveTreeUri(context: Context, uri: Uri) {
        require(DocumentsContract.isTreeUri(uri)) { "Select a folder from the Android document picker." }
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.getString(TREE_URI_KEY, null)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val saved = preferences
            .edit()
            .putString(TREE_URI_KEY, uri.toString())
            .commit()
        if (!saved) {
            if (previous != uri.toString()) releaseTreePermission(context, uri)
            throw IllegalStateException("Could not save the selected download folder.")
        }
        if (previous != null && previous != uri.toString()) {
            runCatching { releaseTreePermission(context, Uri.parse(previous)) }
        }
    }

    fun clearTreeUri(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.getString(TREE_URI_KEY, null)
        val cleared = preferences
            .edit()
            .remove(TREE_URI_KEY)
            .commit()
        if (!cleared) throw IllegalStateException("Could not clear the selected download folder.")
        previous?.let { raw -> runCatching { releaseTreePermission(context, Uri.parse(raw)) } }
    }

    private fun releaseTreePermission(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.releasePersistableUriPermission(uri, flags)
    }

    fun displayName(context: Context, treeUri: Uri): String? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull()
}
