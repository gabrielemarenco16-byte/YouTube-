package it.shortsgen.app.script

import it.shortsgen.app.core.ErrorMessages
import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.retry
import kotlin.random.Random

/** Genera un copione nuovo, evitando gli argomenti già usati. */
class ScriptGenerator(
    private val provider: TextProvider,
    private val history: TopicStore,
    private val random: Random = Random.Default,
) {
    suspend fun generate(onStatus: (String) -> Unit = {}): VideoScript {
        val used = history.all()
        var lastDuplicate: VideoScript? = null
        repeat(3) { round ->
            val prompt = Prompts.scriptPrompt(used, random.nextInt(0, 10_000))
            // anche un JSON non valido (GenerationException) merita un nuovo tentativo
            val script = retry(times = 3, shouldRetry = { it is GenerationException || ErrorMessages.isRetryable(it) }, onRetry = { attempt, _ ->
                onStatus("Nuovo tentativo con ${provider.name} ($attempt)…")
            }) {
                ScriptParser.parse(provider.complete(prompt))
            }
            val duplicate = TopicMatcher.isDuplicate(script.topic, used) ||
                TopicMatcher.isDuplicate(script.title, used)
            if (!duplicate) return script
            lastDuplicate = script
            onStatus("Argomento già usato, ne cerco un altro (${round + 1})…")
        }
        return lastDuplicate ?: throw GenerationException("Impossibile generare un copione nuovo. Riprova.")
    }
}

/** Archivio degli argomenti già usati. */
interface TopicStore {
    fun all(): List<String>
    fun add(topic: String)
}
