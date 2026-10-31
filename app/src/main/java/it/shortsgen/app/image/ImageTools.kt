package it.shortsgen.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import java.io.File
import java.io.FileOutputStream

object ImageTools {
    const val VIDEO_WIDTH = 1080
    const val VIDEO_HEIGHT = 1920

    /** Decodifica limitando la memoria (le immagini generate possono essere grandi). */
    fun decode(bytes: ByteArray, maxSide: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Ritaglia al centro e scala per riempire esattamente [width]x[height]. */
    fun centerCrop(src: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val cropW = (width / scale).toInt().coerceAtMost(src.width)
        val cropH = (height / scale).toInt().coerceAtMost(src.height)
        val left = (src.width - cropW) / 2
        val top = (src.height - cropH) / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, Rect(left, top, left + cropW, top + cropH), Rect(0, 0, width, height), paint)
        return out
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 92) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private val PALETTES = listOf(
        intArrayOf(0xFF1E1B4B.toInt(), 0xFF7C3AED.toInt()),
        intArrayOf(0xFF0F172A.toInt(), 0xFF0EA5E9.toInt()),
        intArrayOf(0xFF3B0764.toInt(), 0xFFDB2777.toInt()),
        intArrayOf(0xFF052E16.toInt(), 0xFF16A34A.toInt()),
        intArrayOf(0xFF431407.toInt(), 0xFFEA580C.toInt()),
        intArrayOf(0xFF172554.toInt(), 0xFF6366F1.toInt()),
        intArrayOf(0xFF1C1917.toInt(), 0xFFCA8A04.toInt()),
    )

    /**
     * Immagine segnaposto usata solo se tutti i servizi falliscono: un gradiente
     * senza testo (i soli testi a schermo devono essere i sottotitoli).
     */
    fun placeholder(index: Int, width: Int = VIDEO_WIDTH, height: Int = VIDEO_HEIGHT): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colors = PALETTES[index % PALETTES.size]
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val glow = Paint().apply {
            shader = RadialGradient(
                width * 0.5f, height * 0.4f, width * 0.8f,
                Color.argb(90, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
        return bmp
    }
}
