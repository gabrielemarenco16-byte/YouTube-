package it.shortsgen.app.image

import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.executeForBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** Immagini con Cloudflare Workers AI (es. @cf/black-forest-labs/flux-1-schnell). */
class CloudflareImageProvider(
    private val accountId: String,
    private val apiToken: String,
    private val model: String,
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://api.cloudflare.com",
) : ImageProvider {
    override val name = "Cloudflare Workers AI"

    override suspend fun generate(prompt: String): ByteArray {
        val isFlux = model.contains("flux", ignoreCase = true)
        val body = buildJsonObject {
            put("prompt", prompt.take(2000))
            if (isFlux) {
                put("steps", 6)
            } else {
                put("width", 768)
                put("height", 1344)
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/client/v4/accounts/${accountId.trim()}/ai/run/${model.trim()}")
            .header("Authorization", "Bearer ${apiToken.trim()}")
            .post(body.toString().toRequestBody(Http.JSON_MEDIA))
            .build()
        val (bytes, contentType) = client.executeForBytes(request, name)
        return decodeResponse(bytes, contentType)
    }

    companion object {
        /** Alcuni modelli rispondono con l'immagine binaria, altri con JSON {result:{image:base64}}. */
        fun decodeResponse(bytes: ByteArray, contentType: String?): ByteArray {
            if (contentType?.startsWith("image/") == true) return bytes
            val text = String(bytes, Charsets.UTF_8)
            val root = runCatching { Http.json.parseToJsonElement(text) as JsonObject }.getOrNull()
                ?: throw GenerationException("Cloudflare ha restituito una risposta non valida.")
            val result = root["result"] as? JsonObject
            val image = (result?.get("image") as? JsonPrimitive)?.contentOrNull
            if (image.isNullOrEmpty()) throw GenerationException("Cloudflare non ha restituito nessuna immagine.")
            return Base64.getMimeDecoder().decode(image)
        }
    }
}
