package it.shortsgen.app.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCheckTest {
    private val narrations = listOf("Did you know octopuses have three hearts?", "Follow us for more curiosities!")

    @Test
    fun builtTimelinePassesTheCheck() {
        val timeline = TimelineBuilder.build(narrations.map { TimelineBuilder.SceneAudio(it, 3000, 3350) })
        val report = SubtitleCheck.check(narrations, timeline.cues, timeline.totalMs)
        assertTrue(report.problems.toString(), report.ok)
    }

    @Test
    fun detectsMissingWord() {
        val cues = listOf(
            SubtitleCue("DID YOU KNOW", 0, 500),
            SubtitleCue("OCTOPUSES HAVE THREE", 500, 1000),
            // "HEARTS?" mancante
            SubtitleCue("FOLLOW US FOR", 1000, 1500),
            SubtitleCue("MORE CURIOSITIES!", 1500, 2000),
        )
        val report = SubtitleCheck.check(narrations, cues, 2000)
        assertFalse(report.ok)
        assertTrue(report.problems.first().contains("HEARTS"))
    }
}
