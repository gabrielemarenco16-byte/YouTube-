package it.shortsgen.app.image

import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.executeForBytes
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random

/** Immagini gratuite da Pollinations.ai: non serve nessuna chiave né registrazione. */
class PollinationsImageProvider(
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://image.pollinations.ai",
    private val random: Random = Random.Default,
) : ImageProvider {
    override val name = "Pollinations (gratis)"

    override suspend fun generate(prompt: String): ByteArray {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("prompt")
            .addPathSegment(prompt.take(600))
            .addQueryParameter("width", "768")
            .addQueryParameter("height", "1344")
            .addQueryParameter("nologo", "true")
            .addQueryParameter("seed", random.nextInt(1, 1_000_000).toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        val (bytes, contentType) = client.executeForBytes(request, name)
        if (contentType != null && !contentType.startsWith("image/")) {
            throw GenerationException("Pollinations non ha restituito un'immagine.")
        }
        return bytes
    }
}
