package com.tg.batterychan.util

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File

/** What kind of picked media an event has, decided from the stored file's extension. */
enum class MediaKind { IMAGE, ANIMATED, VIDEO }

object Media {
    private val videoExt = setOf("mp4", "m4v", "3gp", "3gpp", "webm", "mkv", "mov")
    private val animatedExt = setOf("gif", "webp")

    fun kindOf(path: String): MediaKind {
        val ext = File(path).extension.lowercase()
        return when (ext) {
            in videoExt -> MediaKind.VIDEO
            in animatedExt -> MediaKind.ANIMATED
            else -> MediaKind.IMAGE
        }
    }

    /** width / height of the media as it is displayed, or null if it cannot be read. */
    fun aspectRatio(path: String): Float? {
        return try {
            var w: Int
            var h: Int
            if (kindOf(path) == MediaKind.VIDEO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(path)
                    w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation =
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation == 90 || rotation == 270) {
                        val t = w
                        w = h
                        h = t
                    }
                } finally {
                    r.release()
                }
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                w = o.outWidth
                h = o.outHeight
            }
            if (w > 0 && h > 0) w.toFloat() / h else null
        } catch (e: Exception) {
            null
        }
    }
}
