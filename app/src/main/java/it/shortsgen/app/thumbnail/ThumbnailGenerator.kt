package it.shortsgen.app.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import it.shortsgen.app.image.ImageTools
import it.shortsgen.app.image.MediaKind
import it.shortsgen.app.image.SceneMedia
import java.io.File

/** Copertina 1280x720 con l'immagine della prima scena e il titolo in grande. */
object ThumbnailGenerator {
    const val WIDTH = 1280
    const val HEIGHT = 720

    fun create(source: SceneMedia?, title: String, output: File) {
        val background = source?.takeIf { it.file.exists() }?.let { loadFrame(it) }
        val bmp = if (background != null) {
            ImageTools.centerCrop(background, WIDTH, HEIGHT).also { background.recycle() }
        } else {
            ImageTools.placeholder(0, WIDTH, HEIGHT)
        }
        val canvas = Canvas(bmp)

        // Sfumatura scura a sinistra/in basso per rendere leggibile il testo.
        val shade = Paint().apply {
            shader = LinearGradient(
                0f, HEIGHT * 0.15f, 0f, HEIGHT.toFloat(),
                Color.argb(10, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), shade)

        val text = title.uppercase()
        val maxWidth = (WIDTH * 0.9f).toInt()
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Riduce la dimensione del testo finché il titolo sta in massimo 3 righe.
        var size = 110f
        var layout: StaticLayout
        do {
            size -= 6f
            fill.textSize = size
            layout = build(text, fill, maxWidth)
        } while ((layout.lineCount > 3 || layout.height >= HEIGHT * 0.62f) && size > 44f)
        val stroke = TextPaint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.14f
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
        val strokeLayout = build(text, stroke, maxWidth)

        // Barra colorata d'accento sopra il titolo.
        val top = HEIGHT - layout.height - HEIGHT * 0.07f
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 61, 87) }
        canvas.drawRoundRect(
            (WIDTH - maxWidth) / 2f, top - 34f, (WIDTH - maxWidth) / 2f + 170f, top - 18f, 8f, 8f, accent,
        )

        canvas.save()
        canvas.translate((WIDTH - maxWidth) / 2f, top)
        strokeLayout.draw(canvas)
        layout.draw(canvas)
        canvas.restore()

        ImageTools.saveJpeg(bmp, output, quality = 90)
        bmp.recycle()
    }

    /** Prima scena: l'immagine, oppure un fotogramma a 1 secondo della clip video. */
    private fun loadFrame(media: SceneMedia): Bitmap? = runCatching {
        if (media.kind == MediaKind.VIDEO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(media.path)
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        } else {
            BitmapFactory.decodeFile(media.path)
        }
    }.getOrNull()

    private fun build(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 0.92f)
            .setIncludePad(false)
            .build()
}
