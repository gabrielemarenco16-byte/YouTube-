package it.shortsgen.app.video

/**
 * Le funzioni seguenti sono pure (testabili): calcolano zoom e luminosità
 * in base al tempo trascorso dall'inizio della scena.
 */
object EffectMath {
    fun progress(elapsedUs: Long, durationUs: Long): Float =
        if (durationUs <= 0) 1f else (elapsedUs.toFloat() / durationUs).coerceIn(0f, 1f)

    /** Zoom lento ("Ken Burns"): da 1.0 a [maxScale] (o al contrario). */
    fun zoomScale(elapsedUs: Long, durationUs: Long, zoomIn: Boolean, maxScale: Float): Float {
        val p = smooth(progress(elapsedUs, durationUs))
        return if (zoomIn) 1f + (maxScale - 1f) * p else maxScale - (maxScale - 1f) * p
    }

    /** Luminosità per dissolvenza dal nero/al nero: 0 = nero, 1 = immagine piena. */
    fun fadeFactor(elapsedUs: Long, durationUs: Long, fadeInUs: Long, fadeOutUs: Long): Float {
        val fromStart = if (fadeInUs > 0) elapsedUs.toFloat() / fadeInUs else 1f
        val toEnd = if (fadeOutUs > 0) (durationUs - elapsedUs).toFloat() / fadeOutUs else 1f
        return minOf(1f, fromStart, toEnd).coerceIn(0f, 1f)
    }

    private fun smooth(t: Float): Float = t * t * (3 - 2 * t)
}
