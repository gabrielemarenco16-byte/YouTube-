package it.shortsgen.app.script

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Scene(
    val narration: String,
    @SerialName("image_prompt") val imagePrompt: String,
)

@Serializable
data class VideoScript(
    val topic: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val scenes: List<Scene>,
)
