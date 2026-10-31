package it.shortsgen.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordExtractorTest {
    @Test
    fun queriesStartWithSearchQueryAndAreUnique() {
        val q = KeywordExtractor.queries(
            searchQuery = "Octopus underwater",
            imagePrompt = "A photorealistic octopus swimming in a coral reef, cinematic lighting",
            narration = "Did you know an octopus has three hearts?",
            topic = "Octopus hearts",
        )
        assertEquals("octopus underwater", q.first())
        assertEquals(q.size, q.toSet().size)
        assertTrue(q.contains("octopus swimming coral"))
    }

    @Test
    fun emptySearchQueryFallsBackToPrompt() {
        val q = KeywordExtractor.queries("", "ancient egyptian pyramids at sunset", "", "")
        assertEquals("ancient egyptian pyramids", q.first())
    }
}
