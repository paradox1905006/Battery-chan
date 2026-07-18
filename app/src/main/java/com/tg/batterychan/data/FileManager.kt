package com.tg.batterychan.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FileManager {

    fun saveFile(context: Context, uri: Uri, event: BatteryEvent, type: Prefs.FileType): SaveResult {
        val contentResolver = context.contentResolver
        
        // Validation
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
            if (mimeType != null && !mimeType.startsWith("image/")) {
                return SaveResult.Error("Invalid file type. Please select an image file.")
            }
            if (size > 5 * 1024 * 1024) {
                return SaveResult.Error("File too large. Maximum size is 5MB.")
            }
        }

        val extension = when {
            mimeType?.contains("jpeg") == true || mimeType?.contains("jpg") == true -> "jpg"
            mimeType?.contains("png") == true -> "png"
            mimeType?.contains("mp3") == true -> "mp3"
            mimeType?.contains("wav") == true -> "wav"
            mimeType?.contains("m4a") == true -> "m4a"
            else -> if (type == Prefs.FileType.AUDIO) "mp3" else "jpg"
        }
        
        val destFile = File(context.filesDir, "custom_${event.key}_${type.name.lowercase()}.$extension")
        
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            SaveResult.Success(destFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            SaveResult.Error("Failed to save file: ${e.message}")
        }
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        var size: Long = 0
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1) {
                    size = it.getLong(index)
                }
            }
        }
        return size
    }

    sealed class SaveResult {
        data class Success(val path: String) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        return name ?: uri.path?.let { File(it).name }
    }

    fun getFileInfo(path: String): FileInfo? {
        val file = File(path)
        if (!file.exists()) return null
        return FileInfo(
            name = file.name,
            size = file.length(),
            lastModified = file.lastModified()
        )
    }

    data class FileInfo(val name: String, val size: Long, val lastModified: Long)
}
