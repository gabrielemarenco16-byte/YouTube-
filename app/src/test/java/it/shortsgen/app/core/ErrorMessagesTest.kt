package it.shortsgen.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class ErrorMessagesTest {
    @Test
    fun invalidKeyMessageIsItalian() {
        val msg = ErrorMessages.toItalian(ApiException("Gemini", 403, "{}"), "Copione")
        assertTrue(msg.contains("chiave API non valida"))
    }

    @Test
    fun rateLimitMessage() {
        assertTrue(ErrorMessages.toItalian(ApiException("Groq", 429, ""), "Copione").contains("limite"))
    }

    @Test
    fun noInternetIsDetectedThroughCauses() {
        val wrapped = RuntimeException("x", UnknownHostException("api.groq.com"))
        assertTrue(ErrorMessages.toItalian(wrapped, "Copione").contains("Nessuna connessione"))
    }

    @Test
    fun generationExceptionKeepsItsMessage() {
        val msg = ErrorMessages.toItalian(GenerationException("Messaggio custom"), "Voce")
        assertTrue(msg == "Messaggio custom")
    }
}
