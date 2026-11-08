package com.tg.batterychan.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Tiny replacement for Glide. Shows one local media file per event:
 *  - still images: sampled decode + EXIF rotation
 *  - GIF / animated WebP: played with the platform [AnimatedImageDrawable] (loops forever)
 *  - video: a poster frame (the pop-up itself plays the video)
 */
object ImageLoader {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Decodes a still bitmap for [path], downsampled so neither side is much larger than
     * [maxSize]. For a video this is a poster frame, for a GIF its first frame.
     */
    fun decode(path: String, maxSize: Int): Bitmap? {
        try {
            if (Media.kindOf(path) == MediaKind.VIDEO) {
                return ThumbnailUtils.createVideoThumbnail(File(path), Size(maxSize, maxSize), null)
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(path, options) ?: return null

            if (Media.kindOf(path) != MediaKind.IMAGE) return bitmap
            val degrees = try {
                when (
                    ExifInterface(path).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
            if (degrees == 0f) return bitmap
            val matrix = Matrix().apply { postRotate(degrees) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun decodeAnimated(path: String, maxSize: Int): Drawable? {
        return try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                var sample = 1
                while (info.size.width / (sample * 2) >= maxSize &&
                    info.size.height / (sample * 2) >= maxSize
                ) {
                    sample *= 2
                }
                decoder.setTargetSampleSize(sample)
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Loads [path] into [view] off the main thread; stale results are dropped. */
    fun load(view: ImageView, path: String, maxSize: Int) {
        stopAnimation(view)
        view.tag = path
        executor.execute {
            var drawable: Drawable? = null
            var bitmap: Bitmap? = null
            if (Media.kindOf(path) == MediaKind.ANIMATED) drawable = decodeAnimated(path, maxSize)
            if (drawable == null) bitmap = decode(path, maxSize)
            main.post {
                if (view.tag != path) return@post
                if (drawable != null) {
                    view.setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.let {
                        it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        it.start()
                    }
                } else {
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** Stops any running animation and empties [view]. */
    fun clear(view: ImageView) {
        stopAnimation(view)
        view.tag = null
        view.setImageDrawable(null)
    }

    private fun stopAnimation(view: ImageView) {
        (view.drawable as? AnimatedImageDrawable)?.stop()
    }
}
