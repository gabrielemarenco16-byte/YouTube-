package it.shortsgen.app.core

import kotlinx.coroutines.delay

/**
 * Esegue [block] fino a [times] volte, con attesa esponenziale tra un tentativo e l'altro.
 * Non riprova se [shouldRetry] restituisce false (es. chiave API non valida).
 */
suspend fun <T> retry(
    times: Int = 3,
    initialDelayMs: Long = 1500,
    factor: Double = 2.0,
    shouldRetry: (Throwable) -> Boolean = ErrorMessages::isRetryable,
    onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    block: suspend (attempt: Int) -> T,
): T {
    require(times >= 1)
    var delayMs = initialDelayMs
    var attempt = 1
    while (true) {
        try {
            return block(attempt)
        } catch (e: Throwable) {
            if (attempt >= times || !shouldRetry(e)) throw e
            onRetry(attempt, e)
            delay(delayMs)
            delayMs = (delayMs * factor).toLong()
            attempt++
        }
    }
}
