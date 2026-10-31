package it.shortsgen.app.image

import it.shortsgen.app.core.ApiException
import it.shortsgen.app.core.ErrorMessages
import it.shortsgen.app.core.GenerationException

/**
 * Usa [primary]; se rifiuta le credenziali (401/403), il modello non esiste (404) o la quota è finita (429),
 * passa definitivamente a [secondary] per il resto del video.
 */
class FallbackImageProvider(
    private val primary: ImageProvider,
    private val secondary: ImageProvider,
    private val onSwitch: (reason: String) -> Unit = {},
) : ImageProvider {
    private var useSecondary = false

    override val name: String get() = if (useSecondary) secondary.name else primary.name

    override suspend fun generate(prompt: String): ByteArray {
        if (useSecondary) return secondary.generate(prompt)
        return try {
            primary.generate(prompt)
        } catch (e: Exception) {
            if (!shouldSwitch(e)) throw e
            useSecondary = true
            onSwitch(ErrorMessages.toItalian(e, "Immagini"))
            secondary.generate(prompt)
        }
    }

    companion object {
        fun shouldSwitch(e: Throwable): Boolean = when (e) {
            is ApiException -> e.code == 401 || e.code == 403 || e.code == 404 || e.code == 429
            is GenerationException -> e.cause is ApiException && shouldSwitch(e.cause!!)
            else -> false
        }
    }
}
