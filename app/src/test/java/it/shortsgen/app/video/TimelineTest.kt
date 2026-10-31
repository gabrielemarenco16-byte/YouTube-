package it.shortsgen.app.video

import it.shortsgen.app.voice.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private fun timings(text: String) = text.split(" ").mapIndexed { i, w -> WordTiming(w, i * 100L, i * 100L + 90) }

    @Test
    fun chunksThreeWordsAtATimeWithoutLoneWord() {
        val cues = SubtitleChunker.chunk(timings("one two three four five six seven"))
        assertEquals(listOf("one two three", "four five", "six seven"), cues.map { it.text })
        assertEquals(0L, cues[0].startMs)
        // il primo sottotitolo resta fino all'inizio del secondo
        assertEquals(300L, cues[0].endMs)
    }

    @Test
    fun sentenceEndDoesNotBreakGroups() {
        // 4 parole = 2 + 2: il punto non spezza i gruppi
        assertEquals(listOf("Wow. This", "is true"), SubtitleChunker.chunk(timings("Wow. This is true")).map { it.text })
    }

    @Test
    fun groupSizes() {
        assertEquals(listOf(1), SubtitleChunker.groupSizes(1))
        assertEquals(listOf(3), SubtitleChunker.groupSizes(3))
        assertEquals(listOf(2, 2), SubtitleChunker.groupSizes(4))
        assertEquals(listOf(3, 2), SubtitleChunker.groupSizes(5))
        assertEquals(listOf(3, 3), SubtitleChunker.groupSizes(6))
        assertEquals(listOf(3, 3, 3, 2, 2), SubtitleChunker.groupSizes(13))
    }

    @Test
    fun punctuationTokensStayAttached() {
        assertEquals(listOf("Wait—", "what?"), SubtitleChunker.words("Wait — what?"))
    }

    @Test
    fun everyWordOfTheScriptAppearsInOrder() {
        val narrations = listOf(
            "Did you know that octopuses have three hearts and blue blood?",
            "Two hearts pump blood to the gills, while the third pumps it to the rest of the body.",
            "When an octopus swims, the heart that serves the body actually stops beating!",
            "That's why they prefer crawling — swimming literally exhausts them.",
            "Their blood is blue because it uses copper, not iron, to carry oxygen.",
            "Copper works better in cold water with very little oxygen.",
            "Follow us for more incredible curiosities every day!",
        )
        val timeline = TimelineBuilder.build(narrations.mapIndexed { i, n ->
            TimelineBuilder.SceneAudio(n, speechMs = 3000L + i * 400, slotMs = 3350L + i * 400, voiceStartMs = 120, voiceEndMs = 2900L + i * 400)
        })
        val shown = timeline.cues.flatMap { it.text.split(" ") }
        val expected = narrations.flatMap { SubtitleChunker.words(it) }.map { SubtitleChunker.displayText(it) }
        assertEquals(expected.map { it.trimEnd(',', '.', ';', ':') }, shown.map { it.trimEnd(',', '.', ';', ':') })
        // ogni gruppo ha al massimo 3 parole e i tempi sono in ordine, senza sovrapposizioni
        assertTrue(timeline.cues.all { it.text.split(" ").size <= 3 })
        timeline.cues.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
        timeline.cues.forEach { assertTrue(it.endMs > it.startMs) }
    }

    @Test
    fun lastSubtitleStaysUntilTheEndOfTheVideo() {
        val timeline = TimelineBuilder.build(
            listOf(TimelineBuilder.SceneAudio("Follow us for more curiosities!", speechMs = 2000, slotMs = 2500, voiceEndMs = 1900))
        )
        assertEquals("FOLLOW US FOR", timeline.cues.first().text)
        assertEquals("MORE CURIOSITIES!", timeline.cues.last().text)
        assertEquals(2500L, timeline.cues.last().endMs)
        assertEquals(timeline.totalMs, timeline.cues.last().endMs)
    }

    @Test
    fun subtitlesStartWithTheVoice() {
        val timeline = TimelineBuilder.build(
            listOf(TimelineBuilder.SceneAudio("one two three", speechMs = 2000, slotMs = 2350, voiceStartMs = 300, voiceEndMs = 1700))
        )
        assertEquals(300L, timeline.cues.first().startMs)
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
        assertEquals(3800L, second.endMs)
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
        assertEquals(List(6) { DurationPlanner.BASE_PAUSE_MS } + DurationPlanner.FINAL_PAUSE_MS, normal)
    }
}
