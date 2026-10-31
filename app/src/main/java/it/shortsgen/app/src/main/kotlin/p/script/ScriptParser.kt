package it.shortsgen.app.script

import it.shortsgen.app.core.GenerationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Trasforma la risposta (testo) del modello in un [VideoScript] valido. */
object ScriptParser {
    const val SCENE_COUNT = 7
    private const val MIN_SCENES = 5
    private const val MAX_TITLE = 100
    private const val MAX_DESCRIPTION = 4500

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): VideoScript {
        val obj = try {
            json.parseToJsonElement(extractJson(raw)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw GenerationException("Il modello ha restituito un copione non leggibile (JSON non valido).")

        val title = obj.string("title")?.trim().orEmpty()
        if (title.isEmpty()) throw GenerationException("Il copione generato non ha un titolo.")

        val scenesJson = obj["scenes"] as? JsonArray
            ?: throw GenerationException("Il copione generato non contiene le scene.")
        val scenes = scenesJson.mapNotNull { el ->
            val s = el as? JsonObject ?: return@mapNotNull null
            val narration = (s.string("narration") ?: s.string("text"))?.let(::cleanNarration).orEmpty()
            val prompt = (s.string("image_prompt") ?: s.string("imagePrompt") ?: s.string("image"))?.trim().orEmpty()
            if (narration.isEmpty()) null else Scene(narration, prompt.ifEmpty { narration })
        }
        if (scenes.size < MIN_SCENES) {
            throw GenerationException("Il copione generato ha solo ${scenes.size} scene valide (servono $SCENE_COUNT).")
        }

        val tags = parseTags(obj["tags"]).toMutableList()
        if (tags.none { it.equals("shorts", ignoreCase = true) }) tags.add("shorts")

        var description = obj.string("description")?.trim().orEmpty()
        if (!description.contains("#shorts", ignoreCase = true)) {
            description = (description + "\n\n#shorts").trim()
        }

        val topic = obj.string("topic")?.trim().takeUnless { it.isNullOrEmpty() } ?: title

        return VideoScript(
            topic = topic.take(120),
            title = title.take(MAX_TITLE),
            description = description.take(MAX_DESCRIPTION),
            tags = tags.distinctBy { it.lowercase() }.take(15),
            scenes = scenes.take(SCENE_COUNT),
        )
    }

    /** Elimina eventuali blocchi ```json e testo prima/dopo l'oggetto JSON. */
    fun extractJson(raw: String): String {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return text
        return text.substring(start, end + 1)
    }

    private fun parseTags(element: JsonElement?): List<String> {
        val items = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> element.contentOrNull?.split(',', '#').orEmpty()
            else -> emptyList()
        }
        return items.map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }
    }

    /** Rimuove indicazioni di regia tipo "[music]" o "(pause)" che la voce leggerebbe. */
    fun cleanNarration(text: String): String =
        text.replace(Regex("\\[[^\\]]*]"), " ")
            .replace(Regex("\\((?:pause|music|sfx|beat)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[*_#]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
