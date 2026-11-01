package it.shortsgen.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun subjectIsAddedToEveryQueryAndLastIsSubjectAlone() {
        val q = KeywordExtractor.sceneQueries(
            llmQueries = listOf("glowing in dark water", "jellyfish tentacles", "coral reef"),
            theme = "ocean",
            themeQueries = listOf("sea", "underwater"),
            subject = "jellyfish",
        )
        assertEquals(
            listOf(
                "jellyfish glowing in dark", "jellyfish tentacles", "jellyfish coral reef",
                "jellyfish sea", "jellyfish underwater", "jellyfish ocean", "jellyfish",
            ),
            q.all,
        )
        assertTrue(q.all.all { it.contains("jellyfish") })
        assertEquals(3, q.sceneCount)
    }

    @Test
    fun hookPutsTheMostSpecificQueryFirst() {
        val q = KeywordExtractor.sceneQueries(
            llmQueries = listOf("jellyfish", "jellyfish pulsing glowing tentacles", "jellyfish swimming"),
            theme = "", subject = "jellyfish", hook = true,
        )
        assertEquals("jellyfish pulsing glowing tentacles", q.all.first())
    }

    @Test
    fun pluralSubjectIsNotDuplicated() {
        assertEquals("jellyfishes swimming", KeywordExtractor.withSubject("jellyfishes swimming", "jellyfish"))
        assertEquals("honey bee flower", KeywordExtractor.withSubject("flower", "honey bee"))
    }

    @Test
    fun hookPrefersQueriesAboutTheSubject() {
        val q = KeywordExtractor.sceneQueries(
            llmQueries = listOf("octopus underwater", "octopus swimming", "coral reef"),
            theme = "", subject = "octopus", hook = true,
        )
        assertEquals(listOf("octopus underwater", "octopus swimming", "octopus coral reef", "octopus"), q.all)
    }
}
