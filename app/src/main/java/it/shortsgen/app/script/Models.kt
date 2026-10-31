package it.shortsgen.app.script

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Scene(
    val narration: String,
    @SerialName("image_prompt") val imagePrompt: String,
    /** 2-4 parole chiave inglesi per cercare video/foto di repertorio (Pexels). */
    @SerialName("search_query") val searchQuery: String = "",
)

@Serializable
data class VideoScript(
    val topic: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val scenes: List<Scene>,
)
