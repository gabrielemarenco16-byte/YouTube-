package it.shortsgen.app.image

import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordExtractorTest {
    @Test
    fun llmQueriesInOrderThenTheme() {
        val q = KeywordExtractor.queries(
            llmQueries = listOf("Octopus swimming", "coral reef", "deep ocean", "extra ignored"),
            theme = "Ocean",
        )
        assertEquals(listOf("octopus swimming", "coral reef", "deep ocean", "ocean"), q)
    }

    @Test
    fun themeIsAlwaysLastEvenIfRepeated() {
        val q = KeywordExtractor.queries(listOf("fire", "candle flame"), theme = "fire")
        assertEquals(listOf("candle flame", "fire"), q)
    }

    @Test
    fun withoutLlmQueriesUsesKeywordsFromTheScene() {
        val q = KeywordExtractor.queries(
            llmQueries = emptyList(),
            theme = "",
            imagePrompt = "ancient egyptian pyramids at sunset",
            topic = "Pyramids secrets",
        )
        assertEquals("ancient egyptian pyramids", q.first())
        assertEquals("pyramids", q.last())
    }
}
