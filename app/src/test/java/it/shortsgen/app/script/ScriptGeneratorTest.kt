package it.shortsgen.app.script

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptGeneratorTest {

    private fun json(topic: String) = buildString {
        append("{\"topic\":\"$topic\",\"title\":\"$topic\",\"description\":\"d\",\"tags\":[\"t\"],\"scenes\":[")
        append((1..7).joinToString(",") { "{\"narration\":\"n $it.\",\"image_prompt\":\"p\"}" })
        append("]}")
    }

    private class FakeStore(val items: MutableList<String>) : TopicStore {
        override fun all() = items.toList()
        override fun add(topic: String) { items += topic }
    }

    @Test
    fun skipsAlreadyUsedTopic() = runTest {
        val answers = ArrayDeque(listOf(json("Honey never spoils"), json("Sharks are older than trees")))
        val provider = object : TextProvider {
            override val name = "fake"
            override suspend fun complete(prompt: String) = answers.removeFirst()
        }
        val generator = ScriptGenerator(provider, FakeStore(mutableListOf("Honey never spoils")))
        assertEquals("Sharks are older than trees", generator.generate().topic)
    }

    @Test
    fun retriesOnInvalidJson() = runTest {
        val answers = ArrayDeque(listOf("not json", json("Bananas are berries")))
        val provider = object : TextProvider {
            override val name = "fake"
            override suspend fun complete(prompt: String) = answers.removeFirst()
        }
        // un JSON non valido viene ritentato automaticamente
        val generator = ScriptGenerator(provider, FakeStore(mutableListOf()))
        assertEquals("Bananas are berries", generator.generate().topic)
    }
}
