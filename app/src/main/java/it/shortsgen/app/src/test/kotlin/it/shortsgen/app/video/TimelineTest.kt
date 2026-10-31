package it.shortsgen.app.video

import it.shortsgen.app.voice.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    @Test
    fun chunksThreeWordsAtATime() {
        val words = "one two three four five six seven".split(" ").mapIndexed { i, w ->
            WordTiming(w, i * 100L, i * 100L + 90)
        }
        val cues = SubtitleChunker.chunk(words)
        assertEquals(listOf("one two three", "four five six", "seven"), cues.map { it.text })
        assertEquals(0L, cues[0].startMs)
        // il primo sottotitolo resta fino all'inizio del secondo
        assertEquals(300L, cues[0].endMs)
    }

    @Test
    fun breaksAtSentenceEnd() {
        val words = listOf("Wow.", "This", "is", "true").mapIndexed { i, w -> WordTiming(w, i * 100L, i * 100L + 90) }
        assertEquals(listOf("Wow.", "This is true"), SubtitleChunker.chunk(words).map { it.text })
    }

    @Test
    fun displayTextIsUppercaseWithoutTrailingPunctuation() {
        assertEquals("THREE HEARTS", SubtitleChunker.displayText("three hearts,"))
        assertEquals("REALLY?", SubtitleChunker.displayText("really?"))
    }

    @Test
    fun timelineOffsetsCuesByScene() {
        val timeline = TimelineBuilder.build(
            listOf(
                TimelineBuilder.SceneAudio("first scene words here", 2000, 2400),
                TimelineBuilder.SceneAudio("second one", 1000, 1400),
            )
        )
        assertEquals(listOf(0L, 2400L), timeline.scenes.map { it.startMs })
        assertEquals(3800L, timeline.totalMs)
        val second = timeline.cues.last()
        assertEquals("SECOND ONE", second.text)
        assertTrue(second.startMs >= 2400)
        assertTrue(second.endMs <= 3800)
        timeline.cues.zipWithNext().forEach { (a, b) -> assertTrue(a.startMs <= b.startMs) }
    }

    @Test
    fun plannerSpeedsUpLongVideos() {
        val durations = List(7) { 10_000L } // 70 s
        val rate = DurationPlanner.adjustedRate(durations, 1.0f)!!
        assertTrue(rate > 1.2f && rate <= DurationPlanner.MAX_RATE)
        assertNull(DurationPlanner.adjustedRate(List(7) { 6_000L }, 1.0f))
    }

    @Test
    fun plannerPadsShortVideosToThirtySeconds() {
        val durations = List(7) { 3_000L } // 21 s
        val pauses = DurationPlanner.pauses(durations)
        assertTrue(durations.sum() + pauses.sum() >= DurationPlanner.MIN_TOTAL_MS)
        val normal = DurationPlanner.pauses(List(7) { 6_000L })
        assertEquals(List(7) { DurationPlanner.BASE_PAUSE_MS }, normal)
    }
}
