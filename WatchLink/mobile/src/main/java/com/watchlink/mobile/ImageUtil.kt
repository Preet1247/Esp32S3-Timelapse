package com.watchlink.mobile

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageUtil {
    fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun bitmapToBase64Scaled(bitmap: Bitmap, maxPx: Int): String? = try {
        val scale = min(1f, maxPx.toFloat() / max(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
                true,
            )
        } else bitmap
        bitmapToBase64(scaled)
    } catch (e: Exception) {
        null
    }

    fun drawableToBase64(drawable: Drawable?, maxPx: Int): String? {
        if (drawable == null) return null
        return try {
            var w = drawable.intrinsicWidth
            var h = drawable.intrinsicHeight
            if (w <= 0 || h <= 0) {
                w = maxPx
                h = maxPx
            }
            val scale = min(1f, maxPx.toFloat() / max(w, h))
            val tw = max(1, (w * scale).roundToInt())
            val th = max(1, (h * scale).roundToInt())
            bitmapToBase64(drawable.toBitmap(tw, th, Bitmap.Config.ARGB_8888))
        } catch (e: Exception) {
            null
        }
    }
}
