package com.zevclip.sender

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Parcelable
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

object AndroidNotificationImageEncoder {
    private const val TAG = "ZevClipNotifyImage"
    private const val IMAGE_SIZE_PX = 256

    @Suppress("DEPRECATION")
    fun notificationImagePngBase64(context: Context, notification: Notification): String? {
        return try {
            val extras = notification.extras
            val image = listOfNotNull(
                extras.get(Notification.EXTRA_PICTURE),
                extras.get(Notification.EXTRA_PICTURE_ICON),
                extras.get(Notification.EXTRA_LARGE_ICON_BIG),
                extras.get(Notification.EXTRA_LARGE_ICON),
                notification.getLargeIcon(),
                notification.largeIcon
            ).firstNotNullOfOrNull { candidate ->
                candidate.toBitmap(context)
            } ?: return null

            image.toPngBase64(IMAGE_SIZE_PX)
        } catch (error: Exception) {
            Log.w(TAG, "Could not encode notification image", error)
            null
        }
    }

    private fun Any.toBitmap(context: Context): Bitmap? {
        return when (this) {
            is Bitmap -> this
            is Icon -> loadDrawable(context)?.renderToBitmap(IMAGE_SIZE_PX)
            is Drawable -> renderToBitmap(IMAGE_SIZE_PX)
            is Parcelable -> null
            else -> null
        }
    }

    private fun Drawable.renderToBitmap(maxSizePx: Int): Bitmap {
        val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: maxSizePx
        val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: maxSizePx
        val scale = minOf(
            maxSizePx.toFloat() / sourceWidth.toFloat(),
            maxSizePx.toFloat() / sourceHeight.toFloat(),
            1f
        )
        val drawWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val drawHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(drawWidth, drawHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, drawWidth, drawHeight)
        draw(canvas)
        return bitmap
    }

    private fun Bitmap.toPngBase64(maxSizePx: Int): String? {
        val bitmap = scaledToFit(maxSizePx)
        return ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                return null
            }
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun Bitmap.scaledToFit(maxSizePx: Int): Bitmap {
        val longestSide = maxOf(width, height)
        if (longestSide <= maxSizePx) {
            return this
        }

        val scale = maxSizePx.toFloat() / longestSide.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }
}
