package it.shortsgen.app.video

import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix

/**
 * Il tempo di riferimento è il primo fotogramma ricevuto: così l'effetto funziona sia se
 * i timestamp sono relativi alla scena, sia se sono relativi a tutto il video.
 */
private class SceneClock {
    private var firstUs = C.TIME_UNSET
    fun elapsed(presentationTimeUs: Long): Long {
        if (firstUs == C.TIME_UNSET || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        return presentationTimeUs - firstUs
    }
}

@OptIn(UnstableApi::class)
class KenBurnsEffect(
    private val durationUs: Long,
    private val zoomIn: Boolean,
    private val maxScale: Float = 1.12f,
) : MatrixTransformation {
    private val clock = SceneClock()

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val s = EffectMath.zoomScale(clock.elapsed(presentationTimeUs), durationUs, zoomIn, maxScale)
        return Matrix().apply { postScale(s, s) }
    }
}

@OptIn(UnstableApi::class)
class FadeEffect(
    private val durationUs: Long,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
) : RgbMatrix {
    private val clock = SceneClock()

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val f = EffectMath.fadeFactor(clock.elapsed(presentationTimeUs), durationUs, fadeInUs, fadeOutUs)
        return floatArrayOf(
            f, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, f, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
