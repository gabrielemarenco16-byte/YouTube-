package it.shortsgen.app.script

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Scene(
    val narration: String,
    @SerialName("image_prompt") val imagePrompt: String,
    /** (versioni precedenti) una sola ricerca per scena */
    @SerialName("search_query") val searchQuery: String = "",
    /** 3 ricerche visive concrete in inglese per clip/foto di repertorio, in ordine di preferenza. */
    @SerialName("search_queries") val searchQueries: List<String> = emptyList(),
)

@Serializable
data class VideoScript(
    val topic: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val scenes: List<Scene>,
    /** Tema visivo generico del video (es. "chemistry", "fire", "space"): ricerca di riserva. */
    val theme: String = "",
    /** Ricerche generiche e concrete legate al tema (es. "tree", "forest", "seed pod", "plant"). */
    @SerialName("theme_queries") val themeQueries: List<String> = emptyList(),
    /** Soggetto principale e filmabile del video (es. "jellyfish"): aggiunto a ogni ricerca di clip. */
    val subject: String = "",
    /** Ricerche di riserva per la scena di apertura: soggetto affine più comune, poi il suo ambiente (es. "jellyfish", "deep sea"). */
    @SerialName("subject_queries") val subjectQueries: List<String> = emptyList(),
)
