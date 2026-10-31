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
)

/**
 * Fornitore intercambiabile di immagini/video per le scene.
 * Scrive il file nella cartella [dir] e non deve restituire file con id in [usedIds].
 */
interface ImageProvider {
    val name: String

    suspend fun fetch(request: MediaRequest, dir: File, usedIds: Set<String>): SceneMedia
}
