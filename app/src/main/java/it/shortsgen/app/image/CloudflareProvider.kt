package it.shortsgen.app.image

import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.MissingKeyException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.executeForBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64

/** Immagini AI da Cloudflare Workers AI (CF_ACCOUNT_ID + CF_API_TOKEN). */
class CloudflareProvider(
    private val accountId: String,
    private val apiToken: String,
    private val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://api.cloudflare.com",
) : ImageProvider {
    override val name = NAME

    override suspend fun fetch(request: MediaRequest, dir: File, usedIds: Set<String>): SceneMedia {
        if (accountId.isBlank() || apiToken.isBlank()) {
            throw MissingKeyException(
                "CF_ACCOUNT_ID / CF_API_TOKEN",
                "Credenziali Cloudflare mancanti: inserisci CF_ACCOUNT_ID e CF_API_TOKEN nelle Impostazioni.",
            )
        }
        val body = buildJsonObject {
            put("prompt", request.prompt.take(2000))
            put("steps", 6)
        }
        val httpRequest = Request.Builder()
            .url("$baseUrl/client/v4/accounts/${accountId.trim()}/ai/run/${model.trim()}")
            .header("Authorization", "Bearer ${apiToken.trim()}")
            .post(body.toString().toRequestBody(Http.JSON_MEDIA))
            .build()
        val (bytes, contentType) = client.executeForBytes(httpRequest, NAME)
        val image = decodeResponse(bytes, contentType)
        val target = File(dir, "scene_${request.sceneIndex + 1}.jpg")
        withContext(Dispatchers.Default) {
            val bitmap = ImageTools.decode(image) ?: throw GenerationException("Cloudflare: immagine ricevuta non valida")
            val frame = ImageTools.centerCrop(bitmap, ImageTools.VIDEO_WIDTH, ImageTools.VIDEO_HEIGHT)
            bitmap.recycle()
            ImageTools.saveJpeg(frame, target)
            frame.recycle()
        }
        return SceneMedia(MediaKind.IMAGE, target.absolutePath, NAME, "cloudflare-${request.sceneIndex}-${image.size}")
    }

    companion object {
        const val NAME = "Cloudflare"
        const val DEFAULT_MODEL = "@cf/black-forest-labs/flux-1-schnell"

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
