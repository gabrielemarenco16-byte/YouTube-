package it.shortsgen.app.image

import it.shortsgen.app.core.ApiException
import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.executeForBytes
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.random.Random

/**
 * Immagini gratuite da Pollinations.ai: non serve nessuna chiave né registrazione.
 * Il piano gratuito permette circa 1 immagine ogni 45 secondi: l'attesa è gestita qui.
 */
class PollinationsImageProvider(
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://image.pollinations.ai",
    private val random: Random = Random.Default,
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    /** Avvisa quanti secondi si sta aspettando (per mostrarlo all'utente). */
    private val onWait: (seconds: Int) -> Unit = {},
) : ImageProvider {
    override val name = "Pollinations (gratis)"

    private var lastRequestAt = 0L

    override suspend fun generate(prompt: String): ByteArray {
        var attempt = 0
        while (true) {
            val sinceLast = clock() - lastRequestAt
            if (lastRequestAt > 0 && sinceLast < minIntervalMs) pause(minIntervalMs - sinceLast)
            lastRequestAt = clock()
            try {
                return request(prompt)
            } catch (e: ApiException) {
                val retryAfter = retryAfterMs(e) ?: throw e
                if (++attempt > MAX_LIMIT_RETRIES) throw e
                pause(retryAfter)
                lastRequestAt = 0 // abbiamo già aspettato il tempo richiesto
            }
        }
    }

    private suspend fun pause(ms: Long) {
        onWait(((ms + 999) / 1000).toInt())
        wait(ms)
    }

    private suspend fun request(prompt: String): ByteArray {
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

    companion object {
        const val DEFAULT_INTERVAL_MS = 46_000L
        private const val MAX_LIMIT_RETRIES = 3

        /** Per gli errori di limite (402/429) restituisce quanto aspettare, altrimenti null. */
        fun retryAfterMs(e: ApiException): Long? {
            if (e.code != 402 && e.code != 429) return null
            val seconds = Regex("(?i)retry after (\\d+)\\s*s").find(e.body)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("(?i)every (\\d+) seconds").find(e.body)?.groupValues?.get(1)?.toLongOrNull()
                ?: (DEFAULT_INTERVAL_MS / 1000)
            return (seconds + 2) * 1000
        }
    }
}
