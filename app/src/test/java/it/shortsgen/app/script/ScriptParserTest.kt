package it.shortsgen.app.script

import it.shortsgen.app.core.GenerationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptParserTest {

    private fun sampleJson(scenes: Int = 7, tags: String = "[\"space\", \"#facts\"]") = buildString {
        append("{\"topic\":\"Venus day length\",\"title\":\"A day on Venus is longer than a year\",")
        append("\"description\":\"Mind-blowing fact about Venus.\",\"tags\":$tags,\"scenes\":[")
        append((1..scenes).joinToString(",") {
            "{\"narration\":\"Scene $it narration text.\",\"image_prompt\":\"planet venus $it\"}"
        })
        append("]}")
    }

    @Test
    fun parsesValidJson() {
        val script = ScriptParser.parse(sampleJson())
        assertEquals("Venus day length", script.topic)
        assertEquals(7, script.scenes.size)
        assertEquals("planet venus 3", script.scenes[2].imagePrompt)
        assertTrue(script.tags.contains("facts"))
        assertTrue(script.tags.contains("shorts"))
        assertTrue(script.description.contains("#shorts"))
    }

    @Test
    fun stripsMarkdownFences() {
        val raw = "Here you go:\n```json\n${sampleJson()}\n```\nEnjoy!"
        assertEquals(7, ScriptParser.parse(raw).scenes.size)
    }

    @Test
    fun trimsExtraScenesToSeven() {
        assertEquals(7, ScriptParser.parse(sampleJson(scenes = 9)).scenes.size)
    }

    @Test(expected = GenerationException::class)
    fun rejectsTooFewScenes() {
        ScriptParser.parse(sampleJson(scenes = 3))
    }

    @Test(expected = GenerationException::class)
    fun rejectsGarbage() {
        ScriptParser.parse("sorry, I cannot do that")
    }

    @Test
    fun acceptsTagsAsCommaSeparatedString() {
        val script = ScriptParser.parse(sampleJson(tags = "\"space, planets, #venus\""))
        assertEquals(listOf("space", "planets", "venus", "shorts"), script.tags)
    }

    @Test
    fun removesStageDirections() {
        assertEquals("Did you know this?", ScriptParser.cleanNarration("[music] Did you **know** (pause) this?"))
    }

    @Test
    fun titleIsLimitedTo100Chars() {
        val json = sampleJson().replace("A day on Venus is longer than a year", "x".repeat(150))
        assertEquals(100, ScriptParser.parse(json).title.length)
        assertFalse(ScriptParser.parse(json).title.length > 100)
    }

    @Test
    fun parsesSearchQueriesAndTheme() {
        val json = """{"title":"T","theme":"Chemistry!","scenes":[""" +
            (1..7).joinToString(",") {
                """{"narration":"n $it.","image_prompt":"p","search_queries":["Lab Test Tubes","science","mystery fact","bubbling liquid","beaker"]}"""
            } + "]}"
        val script = ScriptParser.parse(json)
        assertEquals("chemistry", script.theme)
        // le query solo astratte ("science", "mystery fact") vengono scartate; massimo 3
        assertEquals(listOf("lab test tubes", "bubbling liquid", "beaker"), script.scenes[0].searchQueries)
    }
}
