package it.shortsgen.app.script

import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.executeForString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Modello linguistico che restituisce testo (qui: il JSON del copione). */
interface TextProvider {
    val name: String
    suspend fun complete(prompt: String): String
}

class GeminiTextProvider(
    private val apiKey: String,
    private var model: String,
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val onModelChanged: (String) -> Unit = {},
) : TextProvider {
    override val name = "Gemini"

    override suspend fun complete(prompt: String): String =
        GeminiModels.withFallback(model, GeminiModels.Kind.TEXT, apiKey, client, baseUrl, onResolved = {
            model = it
            onModelChanged(it)
        }) { m -> request(prompt, m) }

    private suspend fun request(prompt: String, model: String): String {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", prompt) } }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 1.0)
                put("responseMimeType", "application/json")
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/v1beta/models/${model.trim()}:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .post(body.toString().toRequestBody(Http.JSON_MEDIA))
            .build()
        return extractText(client.executeForString(request, name))
    }

    companion object {
        fun extractText(responseJson: String): String {
            val root = Http.json.parseToJsonElement(responseJson) as JsonObject
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw GenerationException("Gemini non ha restituito nessuna risposta (contenuto bloccato o vuoto). Riprova.")
            val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            val text = parts.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                .joinToString("")
            if (text.isBlank()) throw GenerationException("Gemini ha restituito una risposta vuota. Riprova.")
            return text
        }
    }
}

class GroqTextProvider(
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://api.groq.com",
) : TextProvider {
    override val name = "Groq"

    override suspend fun complete(prompt: String): String {
        val body = buildJsonObject {
            put("model", model.trim())
            put("temperature", 1.0)
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", "You are a helpful assistant that always answers with a single valid JSON object.")
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/openai/v1/chat/completions")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body.toString().toRequestBody(Http.JSON_MEDIA))
            .build()
        return extractText(client.executeForString(request, name))
    }

    companion object {
        fun extractText(responseJson: String): String {
            val root = Http.json.parseToJsonElement(responseJson) as JsonObject
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val content = ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
            if (content.isNullOrBlank()) throw GenerationException("Groq ha restituito una risposta vuota. Riprova.")
            return content
        }
    }
}
