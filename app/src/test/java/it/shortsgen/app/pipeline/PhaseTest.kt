package it.shortsgen.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseTest {
    @Test
    fun weightsSumToOne() {
        assertEquals(1f, Phase.entries.sumOf { it.weight.toDouble() }.toFloat(), 1e-4f)
    }

    @Test
    fun overallProgressIsMonotonic() {
        var last = -1f
        Phase.entries.forEach { phase ->
            listOf(0f, 0.5f, 1f).forEach { p ->
                val overall = phase.overall(p)
                assertTrue(overall >= last)
                last = overall
            }
        }
        assertEquals(1f, Phase.THUMBNAIL.overall(1f), 1e-4f)
        assertEquals(0f, Phase.SCRIPT.overall(0f), 1e-4f)
    }
}
