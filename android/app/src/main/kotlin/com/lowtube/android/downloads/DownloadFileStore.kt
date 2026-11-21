package com.lowtube.android.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import com.lowtube.android.R
import com.lowtube.android.model.DownloadJob
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

internal class DownloadFileStore(private val context: Context) {
    fun partialFile(job: DownloadJob): File = File(job.partialPath)
    fun readyConvertedFile(job: DownloadJob): File {
        val suffix = when {
            job.transcodeMp3 -> ".ready.mp3"
            job.fileName.endsWith(".mkv", ignoreCase = true) -> ".ready.mkv"
            else -> ".ready.mux"
        }
        return File(job.partialPath + suffix)
    }
    fun matroskaFile(job: DownloadJob): File = File(job.partialPath + ".mkv")
    fun videoTrackFile(job: DownloadJob): File = File(job.partialPath + ".video")
    fun audioTrackFile(job: DownloadJob): File = File(job.partialPath + ".audio")
    fun videoTrackTotalFile(job: DownloadJob): File = File(job.partialPath + ".video.total")
    fun audioTrackTotalFile(job: DownloadJob): File = File(job.partialPath + ".audio.total")

    fun trackTotal(job: DownloadJob, video: Boolean): Long = runCatching {
        (if (video) videoTrackTotalFile(job) else audioTrackTotalFile(job)).readText().trim().toLong().coerceAtLeast(0L)
    }.getOrDefault(0L)

    fun saveTrackTotal(job: DownloadJob, video: Boolean, total: Long) {
        if (total <= 0L) return
        val target = if (video) videoTrackTotalFile(job) else audioTrackTotalFile(job)
        val temporary = File(target.parentFile, target.name + ".tmp")
        runCatching {
            temporary.writeText(total.toString())
            if (!temporary.renameTo(target)) {
                target.writeText(total.toString())
                temporary.delete()
            }
        }
    }

    fun deleteTrackFiles(job: DownloadJob) {
        videoTrackFile(job).delete()
        audioTrackFile(job).delete()
        videoTrackTotalFile(job).delete()
        audioTrackTotalFile(job).delete()
        File(videoTrackTotalFile(job).absolutePath + ".tmp").delete()
        File(audioTrackTotalFile(job).absolutePath + ".tmp").delete()
    }

    fun deletePublished(uri: Uri) {
        if (uri.scheme == "file") File(uri.path.orEmpty()).delete()
        else {
            val deletedAsDocument = if (DocumentsContract.isDocumentUri(context, uri)) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            } else false
            if (!deletedAsDocument) context.contentResolver.delete(uri, null, null)
        }
    }

    fun validateDestination() {
        DownloadDestination.selectedTreeUri(context)?.let(::requireTreeWriteAccess)
    }

    private fun requireTreeWriteAccess(treeUri: Uri) {
        if (context.contentResolver.persistedUriPermissions.none { it.uri == treeUri && it.isWritePermission }) {
            throw IOException(context.getString(R.string.download_location_error))
        }
    }

    fun publish(job: DownloadJob, sourceFile: File, cancelled: () -> Boolean): Uri {
        val partial = sourceFile
        if (!partial.isFile || partial.length() <= 0L) throw IOException("The downloaded file is empty or missing.")
        val selectedTree = DownloadDestination.selectedTreeUri(context)
        if (selectedTree != null) {
            requireTreeWriteAccess(selectedTree)
            try {
                return publishToTree(job, partial, selectedTree, cancelled)
            } catch (failure: SecurityException) {
                throw IOException(context.getString(R.string.download_location_error), failure)
            }
        }
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
                context.contentResolver.openOutputStream(uri, "w")?.use { output -> copyForPublication(partial, output, cancelled) }
                    ?: throw IOException("Android could not open the destination file.")
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }.also { context.contentResolver.update(uri, it, null, null) }
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
                copyForPublication(partial, output, cancelled)
            } ?: throw IOException("Android could not open the download entry.")
            return uri
        } catch (failure: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun publishToTree(job: DownloadJob, partial: File, treeUri: Uri, cancelled: () -> Boolean): Uri {
        val parentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val targetUri = DocumentsContract.createDocument(context.contentResolver, parentUri, job.mimeType, job.fileName)
            ?: throw IOException("Android could not create the file in the selected folder.")
        try {
            context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                copyForPublication(partial, output, cancelled)
            } ?: throw IOException("Android could not open the file in the selected folder.")
            return targetUri
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, targetUri) }
            throw failure
        }
    }
    private fun copyForPublication(source: File, output: OutputStream, cancelled: () -> Boolean) {
        FileInputStream(source).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                if (cancelled()) throw CancellationException("Cancelled by user.")
                val size = input.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
            }
        }
    }

}
