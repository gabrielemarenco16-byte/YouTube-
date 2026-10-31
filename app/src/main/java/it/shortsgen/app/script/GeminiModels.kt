package it.shortsgen.app.script

import it.shortsgen.app.core.ApiException
import it.shortsgen.app.core.ErrorMessages
import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.executeForString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Google ritira spesso i modelli Gemini. Quando quello configurato non esiste più (404),
 * chiediamo l'elenco dei modelli disponibili per la chiave e scegliamo il migliore.
 */
object GeminiModels {

    enum class Kind { TEXT, IMAGE }

    /** true se l'errore indica che il modello non esiste o non è utilizzabile. */
    fun isModelNotFound(e: Throwable): Boolean {
        if (e !is ApiException) return false
        if (e.code == 404) return true
        val body = e.body.lowercase()
        return e.code == 400 && ("not found" in body || "is not supported" in body || "no longer available" in body)
    }

    suspend fun listModels(apiKey: String, client: OkHttpClient, baseUrl: String): List<String> {
        val request = Request.Builder()
            .url("$baseUrl/v1beta/models?pageSize=1000")
            .header("x-goog-api-key", apiKey.trim())
            .get()
            .build()
        return parseModelList(client.executeForString(request, "Gemini"))
    }

    /** Nomi (senza "models/") dei modelli che supportano generateContent. */
    fun parseModelList(json: String): List<String> {
        val root = Http.json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val models = root["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { m ->
            val obj = m as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val methods = (obj["supportedGenerationMethods"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (methods != null && "generateContent" !in methods) return@mapNotNull null
            name.removePrefix("models/")
        }
    }

    private val EXCLUDED_ALWAYS = listOf("embedding", "tts", "audio", "live", "aqa", "learnlm", "robotics", "computer-use")

    /** Sceglie il modello più adatto; null se nessuno è adatto. */
    fun pick(models: List<String>, kind: Kind): String? = rank(models, kind).firstOrNull()

    /** Modelli adatti, dal migliore al peggiore. */
    fun rank(models: List<String>, kind: Kind): List<String> {
        val candidates = models.map { it.lowercase() to it }.filter { (lower, _) ->
            lower.startsWith("gemini") && EXCLUDED_ALWAYS.none { it in lower } &&
                when (kind) {
                    Kind.TEXT -> "image" !in lower && "vision" !in lower
                    Kind.IMAGE -> "image" in lower
                }
        }
        return candidates.sortedWith(
            compareBy<Pair<String, String>> { (lower, _) -> if ("preview" in lower || "exp" in lower) 1 else 0 }
                .thenBy { (lower, _) -> if (kind == Kind.TEXT && "flash" !in lower) 1 else 0 }
                .thenBy { (lower, _) -> if ("lite" in lower) 1 else 0 }
                .thenByDescending { (lower, _) -> version(lower) }
                .thenBy { (lower, _) -> if (kind == Kind.TEXT && "pro" in lower) 1 else 0 }
                .thenBy { (lower, _) -> if (lower.endsWith("latest")) 0 else 1 }
                .thenBy { (lower, _) -> lower.length }
        ).map { it.second }
    }

    /** "gemini-2.5-flash" -> 2.5 ; "gemini-flash-latest" -> 0 */
    fun version(name: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /**
     * Esegue [call] con [configured]; se il modello non esiste più ne sceglie un altro
     * dall'elenco e riprova. [onResolved] riceve il nuovo nome da salvare.
     */
    suspend fun <T> withFallback(
        configured: String,
        kind: Kind,
        apiKey: String,
        client: OkHttpClient,
        baseUrl: String,
        onResolved: (String) -> Unit,
        call: suspend (model: String) -> T,
    ): T {
        try {
            return call(configured)
        } catch (e: ApiException) {
            if (!isModelNotFound(e)) throw e
        }
        val available = listModels(apiKey, client, baseUrl)
        val candidates = rank(available.filter { it != configured.trim() }, kind).take(MAX_ATTEMPTS)
        if (candidates.isEmpty()) {
            throw GenerationException(
                if (kind == Kind.IMAGE) {
                    "La tua chiave Gemini non ha accesso a nessun modello per creare immagini. " +
                        "Nelle Impostazioni scegli Cloudflare per le immagini."
                } else {
                    "Il modello \"$configured\" non è disponibile e la tua chiave Gemini non vede altri modelli di testo " +
                        "(modelli trovati: ${available.size}). Controlla la chiave o prova Groq."
                }
            )
        }
        val tried = mutableListOf(configured.trim())
        var lastError: ApiException? = null
        for (candidate in candidates) {
            try {
                val result = call(candidate)
                onResolved(candidate)
                return result
            } catch (e: ApiException) {
                if (!isModelNotFound(e)) throw e
                tried += candidate
                lastError = e
            }
        }
        throw GenerationException(
            "Nessun modello Gemini funziona con la tua chiave (provati: ${tried.joinToString(", ")}). " +
                "Dettaglio di Google: ${lastError?.let { ErrorMessages.apiMessage(it) } ?: "-"}",
            lastError,
        )
    }

    private const val MAX_ATTEMPTS = 6
}
