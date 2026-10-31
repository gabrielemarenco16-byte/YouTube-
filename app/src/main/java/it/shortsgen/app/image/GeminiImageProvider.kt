package it.shortsgen.app.image

import it.shortsgen.app.core.ApiException
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
import java.util.Base64

/** Immagini con l'API Gemini (modelli "image", es. gemini-2.5-flash-image). */
class GeminiImageProvider(
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : ImageProvider {
    override val name = "Gemini Immagini"

    /** Alcuni modelli non accettano imageConfig: dopo un 400 si riprova senza. */
    private var useAspectRatio = true

    override suspend fun generate(prompt: String): ByteArray {
        return try {
            request(prompt, useAspectRatio)
        } catch (e: ApiException) {
            if (e.code == 400 && useAspectRatio) {
                useAspectRatio = false
                request(prompt, false)
            } else {
                throw e
            }
        }
    }

    private suspend fun request(prompt: String, withAspectRatio: Boolean): ByteArray {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", "Generate an image: $prompt") } }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add("TEXT")
                    add("IMAGE")
                }
                if (withAspectRatio) putJsonObject("imageConfig") { put("aspectRatio", "9:16") }
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/v1beta/models/${model.trim()}:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .post(body.toString().toRequestBody(Http.JSON_MEDIA))
            .build()
        return extractImage(client.executeForString(request, name))
    }

    companion object {
        fun extractImage(responseJson: String): ByteArray {
            val root = Http.json.parseToJsonElement(responseJson) as JsonObject
            val candidates = root["candidates"] as? JsonArray
            candidates?.forEach { c ->
                val parts = ((c as? JsonObject)?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                parts?.forEach { p ->
                    val obj = p as? JsonObject ?: return@forEach
                    val inline = (obj["inlineData"] ?: obj["inline_data"]) as? JsonObject ?: return@forEach
                    val data = (inline["data"] as? JsonPrimitive)?.contentOrNull
                    if (!data.isNullOrEmpty()) return Base64.getMimeDecoder().decode(data)
                }
            }
            throw GenerationException("Gemini non ha restituito nessuna immagine per questa scena.")
        }
    }
}
