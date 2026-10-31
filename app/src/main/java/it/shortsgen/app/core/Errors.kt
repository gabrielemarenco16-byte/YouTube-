package it.shortsgen.app.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/** Errore con un messaggio già pronto da mostrare all'utente (in italiano). */
class GenerationException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

/** Risposta HTTP non riuscita da un servizio esterno. */
class ApiException(
    val provider: String,
    val code: Int,
    val body: String,
) : IOException("$provider HTTP $code: ${body.take(300)}")

object ErrorMessages {

    /** Converte qualunque errore in un messaggio chiaro in italiano. */
    fun toItalian(error: Throwable, phase: String): String {
        val root = unwrap(error)
        return when (root) {
            is GenerationException -> root.userMessage
            is ApiException -> apiMessage(root)
            is UnknownHostException ->
                "Nessuna connessione a internet (fase: $phase). Controlla la rete e riprova."
            is SocketTimeoutException ->
                "Il server non ha risposto in tempo (fase: $phase). Riprova tra qualche istante."
            is IOException ->
                "Errore di rete durante la fase \"$phase\": ${root.message ?: "sconosciuto"}."
            is OutOfMemoryError ->
                "Memoria del telefono insufficiente durante la fase \"$phase\". Chiudi altre app e riprova."
            else ->
                "Errore imprevisto durante la fase \"$phase\": ${root.message ?: root.javaClass.simpleName}."
        }
    }

    fun apiMessage(e: ApiException): String = when (e.code) {
        400 -> "${e.provider}: richiesta rifiutata (400). Controlla il nome del modello nelle Impostazioni. Dettaglio: ${shortDetail(e.body)}"
        401, 403 -> "${e.provider}: chiave API non valida o senza permessi (${e.code}). Controllala nelle Impostazioni."
        404 -> "${e.provider}: modello o indirizzo non trovato (404). Controlla il nome del modello nelle Impostazioni. Dettaglio: ${shortDetail(e.body)}"
        429 -> "${e.provider}: limite di richieste gratuite raggiunto (429). Aspetta qualche minuto e riprova."
        in 500..599 -> "${e.provider}: il servizio ha un problema temporaneo (${e.code}). Riprova più tardi."
        else -> "${e.provider}: errore HTTP ${e.code}. ${shortDetail(e.body)}"
    }

    /** Gli errori 4xx (tranne 408/429) non migliorano riprovando. */
    fun isRetryable(error: Throwable): Boolean {
        if (error is CancellationException) return false
        val root = unwrap(error)
        return when (root) {
            is ApiException -> root.code == 408 || root.code == 429 || root.code >= 500
            is GenerationException -> false
            else -> true
        }
    }

    /** Cerca nella catena delle cause l'errore più significativo. */
    private fun unwrap(error: Throwable): Throwable {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 10) {
            if (current is GenerationException || current is ApiException ||
                current is UnknownHostException || current is SocketTimeoutException
            ) return current
            current = current.cause
            depth++
        }
        return error
    }

    private fun shortDetail(body: String): String {
        val match = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)
        return (match?.groupValues?.get(1) ?: body).replace("\n", " ").take(200)
    }
}
