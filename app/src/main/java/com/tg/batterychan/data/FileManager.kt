package com.tg.batterychan.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

object FileManager {

    /**
     * Copies the picked file into app storage. [key] is a built-in event key or a
     * [CustomEvent.key]. Any previously saved file of the same type is deleted first so
     * replacing audio never leaves orphans (the extension can differ between picks).
     */
    fun saveFile(context: Context, uri: Uri, key: String, type: Prefs.FileType): SaveResult {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri)
        val size = getFileSize(context, uri)

        if (type == Prefs.FileType.AUDIO) {
            if (mimeType != null && !mimeType.startsWith("audio/")) {
                return SaveResult.Error("Invalid file type. Please select an audio file.")
            }
            if (size > 10 * 1024 * 1024) {
                return SaveResult.Error("File too large. Maximum size is 10MB.")
            }
        } else {
            val isImage = mimeType?.startsWith("image/") == true
            val isVideo = mimeType?.startsWith("video/") == true
            if (mimeType != null && !isImage && !isVideo) {
                return SaveResult.Error("Invalid file type. Please select an image, GIF or video.")
            }
            val isGif = mimeType == "image/gif" || mimeType == "image/webp"
            val (limit, label) = when {
                isVideo -> 30L to "30MB"
                isGif -> 15L to "15MB"
                else -> 5L to "5MB"
            }
            if (size > limit * 1024 * 1024) {
                return SaveResult.Error("File too large. Maximum size is $label.")
            }
        }

        val fallbackExt = if (type == Prefs.FileType.AUDIO) "mp3" else "jpg"
        val extension = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: fallbackExt

        val prefix = "custom_${key}_${type.tag.lowercase()}"
        val destFile = File(context.filesDir, "$prefix.$extension")

        return try {
            // Write to a temp file first so a failed copy keeps the old file intact.
            val tmp = File(context.filesDir, "$prefix.tmp")
            val input = contentResolver.openInputStream(uri)
                ?: return SaveResult.Error("Could not open the selected file.")
            input.use { src -> FileOutputStream(tmp).use { out -> src.copyTo(out) } }

            context.filesDir.listFiles { f -> f.name.startsWith("$prefix.") && f != tmp }
                ?.forEach { it.delete() }
            if (!tmp.renameTo(destFile)) {
                tmp.delete()
                return SaveResult.Error("Failed to save file.")
            }
            SaveResult.Success(destFile.absolutePath)
        } catch (e: Exception) {
            SaveResult.Error("Failed to save file: ${e.message}")
        }
    }

    /** Deletes all stored media for an event. */
    fun deleteEventFiles(context: Context, key: String) {
        context.filesDir.listFiles { f -> f.name.startsWith("custom_${key}_") }
            ?.forEach { it.delete() }
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1) size = it.getLong(index)
            }
        }
        return size
    }

    fun getFileInfo(path: String): FileInfo? {
        val file = File(path)
        if (!file.exists()) return null
        return FileInfo(file.name, file.length(), file.lastModified())
    }

    sealed class SaveResult {
        data class Success(val path: String) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    data class FileInfo(val name: String, val size: Long, val lastModified: Long)
}
