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
    /** Ultima scelta: scheda grafica con la parola chiave della scena. */
    private val placeholder: suspend (request: MediaRequest, dir: File) -> SceneMedia,
    private val attempts: Int = 3,
    private val retryDelayMs: Long = 2_000,
    private val pauseBetweenScenesMs: Long = 700,
    private val log: (String) -> Unit = {},
    /** Una riga per scena: query usata e file scelto (per i log e la schermata del risultato). */
    private val info: (String) -> Unit = {},
    private val onStatus: (sceneIndex: Int, text: String) -> Unit = { _, _ -> },
) {
    data class Result(val media: List<SceneMedia>, val warnings: List<String>, val sceneLog: List<String> = emptyList()) {
        val placeholderCount: Int get() = media.count { it.placeholder }
    }

    suspend fun collect(requests: List<MediaRequest>, dir: File, existing: List<SceneMedia?> = emptyList()): Result {
        val usedIds = mutableSetOf<String>()
        val warnings = mutableListOf<String>()
        /** Provider esclusi (chiave mancante o rifiutata) con il motivo. */
        val disabled = mutableMapOf<String, String>()
        val media = ArrayList<SceneMedia>()
        val sceneLog = ArrayList<String>()

        requests.forEachIndexed { i, request ->
            val cached = existing.getOrNull(i)
            if (cached != null && !cached.placeholder && cached.file.exists() && cached.sourceId !in usedIds) {
                usedIds += cached.sourceId
                media += cached
                sceneLog += describe(i, request, cached, reused = true).also(info)
                return@forEachIndexed
            }
            var found: SceneMedia? = null
            val errors = mutableListOf<String>()
            for (provider in providers) {
                val disabledReason = disabled[provider.name]
                if (disabledReason != null) {
                    errors += disabledReason
                    continue
                }
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
                        disabled[provider.name] = message
                        warnings += message
                    }
                }
            }
            val result = (found?.let { it.copy(branch = branchOf(request, it)) }) ?: placeholder(request, dir).copy(branch = BRANCH_CARD).also {
                val reason = errors.lastOrNull() ?: "nessun servizio di immagini configurato"
                val warning = "ATTENZIONE: scena ${i + 1} senza clip né foto: usata una scheda grafica con " +
                    "\"${request.keyword.uppercase()}\". Causa: $reason"
                log(warning)
                warnings += warning
            }
            usedIds += result.sourceId
            media += result
            sceneLog += describe(i, request, result, reused = false, cause = errors.lastOrNull()).also(info)
            if (i < requests.lastIndex && found != null) delay(pauseBetweenScenesMs)
        }
        return Result(media, warnings.distinct(), sceneLog)
    }

    /**
     * "Cambia clip": cerca un altro file per una sola scena, escludendo [excludedIds]
     * (tutti i file del video e quelli già scartati). Prova prima il servizio [preferredSource].
     * Restituisce null se non trova nulla (la scena resta com'era).
     */
    suspend fun replace(request: MediaRequest, dir: File, excludedIds: Set<String>, preferredSource: String?): Pair<SceneMedia, String>? {
        val ordered = providers.sortedBy { if (it.name == preferredSource) 0 else 1 }
        for (provider in ordered) {
            try {
                val found = retry(times = attempts, initialDelayMs = retryDelayMs, shouldRetry = ::isRetryable) {
                    provider.fetch(request, dir, excludedIds)
                }
                val media = found.copy(branch = "cambio clip – " + branchOf(request, found))
                val line = describe(request.sceneIndex, request, media, reused = false)
                info(line)
                return media to line
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Cambio clip scena ${request.sceneIndex + 1}: ${provider.name} non ha trovato altro: ${e.message}")
            }
        }
        return null
    }

    private fun describe(i: Int, request: MediaRequest, m: SceneMedia, reused: Boolean, cause: String? = null): String {
        val tried = "ricerche: ${request.queries.joinToString(" → ") { "\"$it\"" }}"
        return if (m.placeholder) {
            "Scena ${i + 1} | ramo: ${m.branch.ifEmpty { BRANCH_CARD }} | parola: \"${request.keyword}\" | file: ${m.file.name} | " +
                "$tried | causa: ${cause ?: "nessun servizio configurato"}"
        } else {
            "Scena ${i + 1} | ramo: ${m.branch} | query usata: \"${m.query}\" | ${m.source} ${m.sourceId} (${m.detail}) | " +
                "file: ${m.file.name}" + (if (reused) " | riutilizzato da tentativo precedente" else "") + " | $tried"
        }
    }

    companion object {
        const val BRANCH_CARD = "scheda grafica (nessuna clip né foto trovata)"

        /** Quale ramo ha prodotto il file: clip/foto, query della scena o del tema, immagine AI. */
        fun branchOf(request: MediaRequest, m: SceneMedia): String {
            if (m.placeholder) return BRANCH_CARD
            if (m.query == "(immagine AI)") return "immagine AI"
            val index = request.queries.indexOf(m.query)
            val origin = if (index in 0 until request.sceneQueryCount) "query della scena" else "query del tema"
            return (if (m.kind == MediaKind.VIDEO) "clip" else "foto") + " – " + origin
        }

        fun isConfigError(e: Throwable): Boolean =
            e is MissingKeyException || (e is ApiException && (e.code == 401 || e.code == 403))

        /** Rete, limiti (429) ed errori del server meritano un nuovo tentativo; il resto no. */
        fun isRetryable(e: Throwable): Boolean =
            e !is CancellationException && !isConfigError(e) && ErrorMessages.isRetryable(e)
    }
}
