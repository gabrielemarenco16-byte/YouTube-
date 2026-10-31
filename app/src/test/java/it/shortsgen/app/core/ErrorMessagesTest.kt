package it.shortsgen.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class ErrorMessagesTest {
    @Test
    fun invalidKeyMessageIsItalian() {
        val msg = ErrorMessages.toItalian(ApiException("Gemini", 403, "{}"), "Copione")
        assertTrue(msg.contains("chiave mancante o non valida"))
        assertTrue(msg.contains("GEMINI_API_KEY"))
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

    @Test
    fun pexelsAndCloudflareMessagesNameTheVariable() {
        assertTrue(ErrorMessages.toItalian(ApiException("Pexels", 401, "{}"), "Immagini").contains("PEXELS_API_KEY"))
        val cf = ErrorMessages.toItalian(ApiException("Cloudflare", 403, """{"errors":[{"message":"Authentication error"}]}"""), "Immagini")
        assertTrue(cf.contains("CF_ACCOUNT_ID / CF_API_TOKEN"))
        assertTrue(cf.contains("Authentication error"))
    }
}
