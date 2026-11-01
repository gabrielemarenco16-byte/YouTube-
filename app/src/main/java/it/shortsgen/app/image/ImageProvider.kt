package it.shortsgen.app.image

import kotlinx.serialization.Serializable
import java.io.File

enum class MediaKind { VIDEO, IMAGE }

/** File visivo pronto per una scena (clip video o immagine 1080x1920). */
@Serializable
data class SceneMedia(
    val kind: MediaKind,
    val path: String,
    /** Servizio che l'ha fornito (es. "Pexels"), oppure "segnaposto". */
    val source: String,
    /** Identificativo univoco del file presso il servizio, per non riusarlo nello stesso video. */
    val sourceId: String = "",
    val placeholder: Boolean = false,
    /** Ricerca che ha trovato il file (vuota per segnaposto/AI). */
    val query: String = "",
    /** Descrizione breve del file scelto (es. "video 1080x1920, 15 s"). */
    val detail: String = "",
    /** Ramo usato per trovarlo (es. "clip – query della scena", "foto – query del tema", "scheda grafica"). */
    val branch: String = "",
    /** Durata della clip originale (0 se sconosciuta o immagine). */
    val durationMs: Long = 0,
    /** Da dove iniziare la clip (usato quando la clip di un'altra scena viene riutilizzata). */
    val startMs: Long = 0,
    /** Descrizione/tag del file presso il servizio. */
    val description: String = "",
    /** Se la clip è presa da un'altra scena: indice (da 0) di quella scena, altrimenti -1. */
    val reusedFromScene: Int = -1,
    /** Punteggio di movimento (solo per le clip confrontate, es. l'hook). */
    val motion: Double = -1.0,
    /** true se il file era già stato usato in un video precedente (nessuna alternativa nuova). */
    val previouslyUsed: Boolean = false,
) {
    val file: File get() = File(path)
}

/** Cosa serve per una scena. */
data class MediaRequest(
    val sceneIndex: Int,
    /** Ricerche (parole chiave inglesi) dalla più precisa alla più generica. */
    val queries: List<String>,
    /** Descrizione dettagliata per i generatori di immagini AI. */
    val prompt: String,
    /** Durata minima che deve avere una clip video. */
    val minDurationMs: Long,
    /** Quante delle [queries] (le prime) sono specifiche della scena; le altre sono del tema. */
    val sceneQueryCount: Int = queries.size,
    /** Parola chiave da mostrare sulla scheda grafica se non si trova nessuna clip né foto. */
    val keyword: String = queries.firstOrNull().orEmpty(),
    /** false = scartare clip/foto con persone (il copione non parla di persone). */
    val allowPeople: Boolean = true,
    /** Soggetto principale del video (le sue parole non fanno scartare un file come "grafico"). */
    val subject: String = "",
    /** Controllo di pertinenza (tag in comune con soggetto/sinonimi/habitat, niente tag fuori ambiente). */
    val relevance: Relevance = Relevance(),
    /** ID già usati in video precedenti: si usano solo se non ci sono alternative. */
    val previouslyUsed: Set<String> = emptySet(),
    /** Scena di apertura: le ricerche sono il soggetto e le sue riserve. */
    val hook: Boolean = false,
    /** Quante clip candidate scaricare (per l'hook se ne confrontano alcune e si tiene la più movimentata). */
    val candidates: Int = 1,
)

/**
 * Fornitore intercambiabile di immagini/video per le scene.
 * Scrive il file nella cartella [dir] e non deve restituire file con id in [usedIds].
 */
interface ImageProvider {
    val name: String

    suspend fun fetch(request: MediaRequest, dir: File, usedIds: Set<String>): SceneMedia

    /**
     * Fino a [MediaRequest.candidates] file diversi (per scegliere la clip più d'impatto).
     * Di base restituisce un solo file.
     */
    suspend fun fetchCandidates(request: MediaRequest, dir: File, usedIds: Set<String>): List<SceneMedia> =
        listOf(fetch(request, dir, usedIds))
}
