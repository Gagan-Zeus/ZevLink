package com.zevclip.sender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

object AndroidPackageIconLoader {
    private const val TAG = "ZevClipIconLoader"
    private const val APP_ICON_SIZE_PX = 128

    fun appIconPngBase64ForPackage(context: Context, packageName: String): String? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawable.renderToBitmap(APP_ICON_SIZE_PX)
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Log.w(TAG, "Could not load icon for $packageName", error)
            null
        }
    }

    private fun Drawable.renderToBitmap(sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: sizePx
        val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: sizePx
        val scale = minOf(
            sizePx.toFloat() / sourceWidth.toFloat(),
            sizePx.toFloat() / sourceHeight.toFloat()
        )
        val drawWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val drawHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val left = (sizePx - drawWidth) / 2
        val top = (sizePx - drawHeight) / 2

        setBounds(left, top, left + drawWidth, top + drawHeight)
        draw(canvas)
        return bitmap
    }
}
