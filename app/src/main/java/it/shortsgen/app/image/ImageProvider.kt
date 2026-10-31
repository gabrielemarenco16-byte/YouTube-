package it.shortsgen.app.image

/** Generatore di immagini intercambiabile: restituisce i byte (PNG/JPEG) dell'immagine. */
interface ImageProvider {
    val name: String
    suspend fun generate(prompt: String): ByteArray
}
