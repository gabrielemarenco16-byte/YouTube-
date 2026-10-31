package it.shortsgen.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay

/**
 * Overlay dei sottotitoli: una bitmap trasparente grande quanto il video su cui
 * viene disegnato il gruppo di parole corrente (bianco con bordo nero).
 * La bitmap viene ridisegnata solo quando cambia il sottotitolo; Media3 la ricarica
 * sulla GPU perché cambia il suo generationId.
 */
@OptIn(UnstableApi::class)
class SubtitleOverlay(
    private val cues: List<SubtitleCue>,
    private val width: Int,
    private val height: Int,
) : BitmapOverlay() {

    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private var currentIndex = Int.MIN_VALUE
    private var firstUs = C.TIME_UNSET

    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = width * 0.085f
    }
    private val stroke = TextPaint(fill).apply {
        style = Paint.Style.STROKE
        strokeWidth = width * 0.016f
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val highlight = TextPaint(fill).apply { color = Color.rgb(255, 221, 0) }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstUs == C.TIME_UNSET || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val ms = (presentationTimeUs - firstUs) / 1000
        val index = SubtitleChunker.cueIndexAt(cues, ms)
        if (index != currentIndex) {
            currentIndex = index
            redraw(cues.getOrNull(index))
        }
        return bitmap
    }

    private fun redraw(cue: SubtitleCue?) {
        bitmap.eraseColor(Color.TRANSPARENT)
        if (cue == null) return
        val maxWidth = (width * 0.86f).toInt()
        // Un gruppo su due in giallo, per dare ritmo come negli Short più visti.
        val paint = if (currentIndex % 2 == 0) fill else highlight
        val strokeLayout = layout(cue.text, stroke, maxWidth)
        val fillLayout = layout(cue.text, paint, maxWidth)
        canvas.save()
        canvas.translate((width - maxWidth) / 2f, height * 0.66f - fillLayout.height / 2f)
        strokeLayout.draw(canvas)
        fillLayout.draw(canvas)
        canvas.restore()
    }

    private fun layout(text: String, paint: TextPaint, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.95f)
            .setIncludePad(false)
            .build()
}
