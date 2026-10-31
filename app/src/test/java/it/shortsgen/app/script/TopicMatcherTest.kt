package it.shortsgen.app.script

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicMatcherTest {
    @Test
    fun detectsSameTopicWithDifferentWording() {
        val used = listOf("Why octopuses have three hearts")
        assertTrue(TopicMatcher.isDuplicate("Octopus has three hearts", used))
    }

    @Test
    fun differentTopicsAreNotDuplicates() {
        val used = listOf("Why octopuses have three hearts", "Honey never spoils")
        assertFalse(TopicMatcher.isDuplicate("The loudest animal on Earth", used))
    }

    @Test
    fun emptyHistoryNeverDuplicates() {
        assertFalse(TopicMatcher.isDuplicate("Anything", emptyList()))
    }
}
