package com.lowtube.android.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets

/** Keeps the last non-secret account status so it remains available for copying after recreation. */
class GoogleAccountMessageStore(context: Context, fileName: String = DEFAULT_FILE_NAME) {
    private val messageFile: AtomicFile

    init {
        require(fileName.matches(FILE_NAME_PATTERN))
        messageFile = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))
    }

    fun load(): String = runCatching {
        messageFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText().take(MAX_MESSAGE_LENGTH) }
    }.getOrDefault("")

    fun save(message: String) {
        if (message.isBlank()) {
            messageFile.delete()
            return
        }
        val output = runCatching { messageFile.startWrite() }.getOrNull() ?: return
        try {
            output.write(message.take(MAX_MESSAGE_LENGTH).toByteArray(StandardCharsets.UTF_8))
            messageFile.finishWrite(output)
        } catch (failure: Exception) {
            messageFile.failWrite(output)
        }
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "google_account_message.txt"
        private val FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
        private const val MAX_MESSAGE_LENGTH = 4096
    }
}
