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
    fun themeEqualToASceneQueryIsNotRepeated() {
        val q = KeywordExtractor.queries(listOf("fire", "candle flame"), theme = "fire")
        assertEquals(listOf("fire", "candle flame"), q)
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

    @Test
    fun sceneQueriesThenThemeQueriesThenTheme() {
        val q = KeywordExtractor.sceneQueries(
            llmQueries = listOf("seed pod", "dandelion seeds", "wind"),
            theme = "plants",
            themeQueries = listOf("tree", "forest", "seed pod", "plant"),
        )
        assertEquals(listOf("seed pod", "dandelion seeds", "wind", "tree", "forest", "plant", "plants"), q.all)
        assertEquals(3, q.sceneCount)
    }
}
