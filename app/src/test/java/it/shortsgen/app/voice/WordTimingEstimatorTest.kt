package it.shortsgen.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingEstimatorTest {
    @Test
    fun coversWholeDurationInOrder() {
        val timings = WordTimingEstimator.estimate("Octopuses have three hearts, and blue blood.", 3000)
        assertEquals(7, timings.size)
        assertEquals(0L, timings.first().startMs)
        assertTrue(timings.last().endMs in 2950..3000)
        timings.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
    }

    @Test
    fun longerWordsLastLonger() {
        val t = WordTimingEstimator.estimate("a extraordinarily", 2000)
        assertTrue(t[1].endMs - t[1].startMs > t[0].endMs - t[0].startMs)
    }

    @Test
    fun emptyText() {
        assertTrue(WordTimingEstimator.estimate("   ", 1000).isEmpty())
    }
}
