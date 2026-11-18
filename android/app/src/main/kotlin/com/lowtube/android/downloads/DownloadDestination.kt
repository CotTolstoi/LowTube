package com.lowtube.android.downloads

import android.content.Context
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

    fun saveTreeUri(context: Context, uri: Uri) {
        require(DocumentsContract.isTreeUri(uri)) { "Select a folder from the Android document picker." }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(TREE_URI_KEY, uri.toString())
            .apply()
    }

    fun clearTreeUri(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(TREE_URI_KEY)
            .apply()
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
