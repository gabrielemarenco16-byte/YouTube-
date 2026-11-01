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
    /** Misura del movimento di una clip (per scegliere la più d'impatto nell'hook); null = non misurare. */
    private val motionScore: (suspend (SceneMedia) -> Double)? = null,
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
        val media = arrayOfNulls<SceneMedia>(requests.size)
        val causes = arrayOfNulls<String>(requests.size)
        val cachedFlags = BooleanArray(requests.size)

        requests.forEachIndexed { i, request ->
            val cached = existing.getOrNull(i)
            if (cached != null && !cached.placeholder && cached.reusedFromScene < 0 && cached.file.exists() && cached.sourceId !in usedIds) {
                usedIds += cached.sourceId
                media[i] = cached
                cachedFlags[i] = true
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
                    val candidates = retry(
                        times = attempts,
                        initialDelayMs = retryDelayMs,
                        shouldRetry = ::isRetryable,
                        onRetry = { attempt, e ->
                            log("Scena ${i + 1}: ${provider.name} tentativo $attempt fallito: ${e.message}")
                            onStatus(i, "Scena ${i + 1}: ${provider.name}, nuovo tentativo (${attempt + 1} di $attempts)…")
                        },
                    ) {
                        if (request.candidates > 1 && motionScore != null) provider.fetchCandidates(request, dir, usedIds)
                        else listOf(provider.fetch(request, dir, usedIds))
                    }
                    found = pickMostDynamic(i, candidates)
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
            if (found != null) {
                val result = found.copy(branch = branchOf(request, found))
                usedIds += result.sourceId
                media[i] = result
                if (i < requests.lastIndex) delay(pauseBetweenScenesMs)
            } else {
                causes[i] = errors.lastOrNull() ?: "nessun servizio di immagini configurato"
            }
        }

        // Scene senza clip né foto del soggetto: si riusa la clip di un'altra scena (un tratto diverso),
        // mai una clip generica. Solo se nel video non c'è nessuna clip si usa la scheda grafica.
        val nextStart = mutableMapOf<Int, Long>()
        val reuseCount = mutableMapOf<Int, Int>()
        for (i in requests.indices) {
            if (media[i] != null) continue
            val donors = requests.indices.filter { j ->
                val m = media[j]
                m != null && !m.placeholder && m.reusedFromScene < 0
            }
            val donor = donors.sortedWith(
                compareBy<Int> { if (media[it]!!.kind == MediaKind.VIDEO) 0 else 1 }
                    .thenBy { reuseCount[it] ?: 0 }
                    .thenBy { kotlin.math.abs(it - i) }
            ).firstOrNull()
            if (donor != null) {
                val d = media[donor]!!
                val need = requests[i].minDurationMs
                val start = reuseStart(d, requests[donor].minDurationMs, nextStart[donor], need)
                nextStart[donor] = start + need
                reuseCount[donor] = (reuseCount[donor] ?: 0) + 1
                media[i] = d.copy(
                    branch = "riuso clip della scena ${donor + 1} (nessuna clip del soggetto per questa scena)",
                    reusedFromScene = donor,
                    startMs = start,
                    motion = -1.0,
                )
                val note = "Scena ${i + 1}: nessuna clip del soggetto trovata, riusato un tratto della clip della scena ${donor + 1}."
                log(note)
                warnings += note
            } else {
                val request = requests[i]
                media[i] = placeholder(request, dir).copy(branch = BRANCH_CARD)
                val warning = "ATTENZIONE: scena ${i + 1} senza clip né foto: usata una scheda grafica con " +
                    "\"${request.keyword.uppercase()}\". Causa: ${causes[i]}"
                log(warning)
                warnings += warning
            }
        }

        val list = media.map { it!! }
        val sceneLog = list.mapIndexed { i, m ->
            describe(i, requests[i], m, reused = cachedFlags[i], cause = causes[i]).also(info)
        }
        return Result(list, warnings.distinct(), sceneLog)
    }

    /** Tra più clip candidate (scena di apertura) tiene quella con più movimento e cancella le altre. */
    private suspend fun pickMostDynamic(sceneIndex: Int, candidates: List<SceneMedia>): SceneMedia {
        val scorer = motionScore
        if (candidates.size == 1 || scorer == null) return candidates.first()
        val scored = candidates.map { c ->
            val score = if (c.kind == MediaKind.VIDEO) runCatching { scorer(c) }.getOrDefault(0.0) else 0.0
            c.copy(motion = score)
        }
        val best = scored.maxByOrNull { it.motion }!!
        log(
            "Scena ${sceneIndex + 1}: movimento delle clip candidate " +
                scored.joinToString(", ") { "${it.sourceId}=${"%.1f".format(it.motion)}" } + " → scelta ${best.sourceId}"
        )
        scored.filter { it !== best && it.path != best.path }.forEach { File(it.path).delete() }
        return best
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
                "file: ${m.file.name}" +
                (if (m.startMs > 0) " | da ${m.startMs / 1000.0} s" else "") +
                (if (m.motion >= 0) " | movimento ${"%.1f".format(m.motion)}" else "") +
                (if (m.description.isNotBlank()) " | descrizione: ${m.description.take(80)}" else "") +
                (if (reused) " | riutilizzato da tentativo precedente" else "") + " | $tried"
        }
    }

    companion object {
        /**
         * Da dove far partire una clip riutilizzata: dopo il tratto già usato se c'è spazio,
         * altrimenti la parte finale della clip, altrimenti dall'inizio.
         */
        fun reuseStart(donor: SceneMedia, donorUsedMs: Long, nextFree: Long?, needMs: Long): Long {
            if (donor.kind != MediaKind.VIDEO || donor.durationMs <= 0) return 0
            // margine: la durata dichiarata dai servizi è arrotondata al secondo
            val usable = donor.durationMs - 500
            val after = nextFree ?: (donor.startMs + donorUsedMs)
            return when {
                after + needMs <= usable -> after
                usable > needMs -> usable - needMs
                else -> 0
            }
        }

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
