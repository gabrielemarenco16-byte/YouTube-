package it.shortsgen.app.image

import kotlin.math.abs

/** Misura del movimento di una clip confrontando fotogrammi rimpiccioliti (logica pura). */
object MotionMath {
    /** Luminosità (0-255) di un pixel ARGB. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Differenza media di luminosità tra due fotogrammi delle stesse dimensioni (0 = fermo). */
    fun difference(a: IntArray, b: IntArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        var sum = 0L
        for (i in a.indices) sum += abs(luma(a[i]) - luma(b[i]))
        return sum.toDouble() / a.size
    }

    /** Punteggio di movimento: media delle differenze tra fotogrammi consecutivi. */
    fun score(frames: List<IntArray>): Double {
        if (frames.size < 2) return 0.0
        return frames.zipWithNext { a, b -> difference(a, b) }.average()
    }
}
