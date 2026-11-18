package com.lowtube.android.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import com.lowtube.android.model.DownloadJob
import java.io.File
import java.io.FileInputStream
import java.io.IOException

internal class DownloadFileStore(private val context: Context) {
    fun partialFile(job: DownloadJob): File = File(job.partialPath)

    fun deletePublished(uri: Uri) {
        if (uri.scheme == "file") File(uri.path.orEmpty()).delete()
        else {
            val deletedAsDocument = if (DocumentsContract.isDocumentUri(context, uri)) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            } else false
            if (!deletedAsDocument) context.contentResolver.delete(uri, null, null)
        }
    }

    fun publish(job: DownloadJob): Uri {
        val partial = partialFile(job)
        if (!partial.isFile || partial.length() <= 0L) throw IOException("The downloaded file is empty or missing.")
        val selectedTree = DownloadDestination.selectedTreeUri(context)
        if (selectedTree != null) return publishToTree(job, partial, selectedTree)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, job.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, job.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LowTube")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Android could not create the download file.")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { output -> FileInputStream(partial).use { it.copyTo(output) } }
                    ?: throw IOException("Android could not open the destination file.")
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }.also { context.contentResolver.update(uri, it, null, null) }
                partial.delete()
                return uri
            } catch (failure: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw failure
            }
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = File(downloads, job.fileName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, target.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, job.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, job.mimeType)
        }
        val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: throw IOException("Android could not create the download entry.")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(partial).use { it.copyTo(output) }
            } ?: throw IOException("Android could not open the download entry.")
            partial.delete()
            return uri
        } catch (failure: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun publishToTree(job: DownloadJob, partial: File, treeUri: Uri): Uri {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val targetUri = DocumentsContract.createDocument(context.contentResolver, parentUri, job.mimeType, job.fileName)
            ?: throw IOException("Android could not create the file in the selected folder.")
        try {
            context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                FileInputStream(partial).use { it.copyTo(output) }
            } ?: throw IOException("Android could not open the file in the selected folder.")
            partial.delete()
            return targetUri
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, targetUri) }
            throw failure
        }
    }
}
