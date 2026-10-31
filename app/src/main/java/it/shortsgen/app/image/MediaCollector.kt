package it.shortsgen.app.image

import it.shortsgen.app.core.ApiException
import it.shortsgen.app.core.ErrorMessages
import it.shortsgen.app.core.MissingKeyException
import it.shortsgen.app.core.retry
import kotlinx.coroutines.delay
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Procura un file visivo per ogni scena:
 * 1) il provider principale (3 tentativi con pause), 2) gli altri provider,
 * 3) solo alla fine un segnaposto, con avviso nei log e a schermo.
 * Lo stesso file non viene mai usato due volte nello stesso video.
 */
class MediaCollector(
    private val providers: List<ImageProvider>,
    private val placeholder: suspend (sceneIndex: Int, dir: File) -> SceneMedia,
    private val attempts: Int = 3,
    private val retryDelayMs: Long = 2_000,
    private val pauseBetweenScenesMs: Long = 700,
    private val log: (String) -> Unit = {},
    private val onStatus: (sceneIndex: Int, text: String) -> Unit = { _, _ -> },
) {
    data class Result(val media: List<SceneMedia>, val warnings: List<String>) {
        val placeholderCount: Int get() = media.count { it.placeholder }
    }

    suspend fun collect(requests: List<MediaRequest>, dir: File, existing: List<SceneMedia?> = emptyList()): Result {
        val usedIds = mutableSetOf<String>()
        val warnings = mutableListOf<String>()
        val disabled = mutableSetOf<String>()
        val media = ArrayList<SceneMedia>()

        requests.forEachIndexed { i, request ->
            val cached = existing.getOrNull(i)
            if (cached != null && !cached.placeholder && cached.file.exists() && cached.sourceId !in usedIds) {
                usedIds += cached.sourceId
                media += cached
                return@forEachIndexed
            }
            var found: SceneMedia? = null
            val errors = mutableListOf<String>()
            for (provider in providers) {
                if (provider.name in disabled) continue
                onStatus(i, "Scena ${i + 1} di ${requests.size}: cerco con ${provider.name}…")
                try {
                    found = retry(
                        times = attempts,
                        initialDelayMs = retryDelayMs,
                        shouldRetry = ::isRetryable,
                        onRetry = { attempt, e ->
                            log("Scena ${i + 1}: ${provider.name} tentativo $attempt fallito: ${e.message}")
                            onStatus(i, "Scena ${i + 1}: ${provider.name}, nuovo tentativo (${attempt + 1} di $attempts)…")
                        },
                    ) { provider.fetch(request, dir, usedIds) }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = ErrorMessages.toItalian(e, "Immagini")
                    errors += message
                    log("Scena ${i + 1}: ${provider.name} non disponibile: $message")
                    if (isConfigError(e)) {
                        // chiave mancante o rifiutata: inutile riprovarla nelle scene successive
                        disabled += provider.name
                        warnings += message
                    }
                }
            }
            val result = found ?: placeholder(i, dir).also {
                val reason = errors.lastOrNull() ?: "nessun servizio di immagini configurato"
                val warning = "ATTENZIONE: scena ${i + 1} senza immagine (segnaposto). Causa: $reason"
                log(warning)
                warnings += warning
            }
            usedIds += result.sourceId
            media += result
            if (i < requests.lastIndex && found != null) delay(pauseBetweenScenesMs)
        }
        return Result(media, warnings.distinct())
    }

    companion object {
        fun isConfigError(e: Throwable): Boolean =
            e is MissingKeyException || (e is ApiException && (e.code == 401 || e.code == 403))

        /** Rete, limiti (429) ed errori del server meritano un nuovo tentativo; il resto no. */
        fun isRetryable(e: Throwable): Boolean =
            e !is CancellationException && !isConfigError(e) && ErrorMessages.isRetryable(e)
    }
}
