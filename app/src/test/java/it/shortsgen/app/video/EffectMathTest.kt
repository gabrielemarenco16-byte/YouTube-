package it.shortsgen.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectMathTest {
    @Test
    fun zoomGoesFromOneToMax() {
        assertEquals(1f, EffectMath.zoomScale(0, 1_000_000, zoomIn = true, maxScale = 1.2f), 1e-4f)
        assertEquals(1.2f, EffectMath.zoomScale(1_000_000, 1_000_000, zoomIn = true, maxScale = 1.2f), 1e-4f)
        assertEquals(1.2f, EffectMath.zoomScale(0, 1_000_000, zoomIn = false, maxScale = 1.2f), 1e-4f)
        // oltre la fine resta fermo
        assertEquals(1.2f, EffectMath.zoomScale(5_000_000, 1_000_000, zoomIn = true, maxScale = 1.2f), 1e-4f)
    }

    @Test
    fun fadeInAndOut() {
        val d = 3_000_000L
        assertEquals(0f, EffectMath.fadeFactor(0, d, 200_000, 200_000), 1e-4f)
        assertEquals(0.5f, EffectMath.fadeFactor(100_000, d, 200_000, 200_000), 1e-4f)
        assertEquals(1f, EffectMath.fadeFactor(1_500_000, d, 200_000, 200_000), 1e-4f)
        assertEquals(0f, EffectMath.fadeFactor(d, d, 200_000, 200_000), 1e-4f)
        // senza dissolvenza iniziale il primo fotogramma è pieno
        assertEquals(1f, EffectMath.fadeFactor(0, d, 0, 200_000), 1e-4f)
    }

    @Test
    fun cueLookup() {
        val cues = listOf(SubtitleCue("A", 0, 500), SubtitleCue("B", 500, 900), SubtitleCue("C", 1200, 1500))
        assertEquals(0, SubtitleChunker.cueIndexAt(cues, 0))
        assertEquals(1, SubtitleChunker.cueIndexAt(cues, 500))
        assertEquals(-1, SubtitleChunker.cueIndexAt(cues, 1000))
        assertEquals(2, SubtitleChunker.cueIndexAt(cues, 1499))
        assertEquals(-1, SubtitleChunker.cueIndexAt(cues, 1500))
        assertEquals(-1, SubtitleChunker.cueIndexAt(emptyList(), 10))
    }
}
