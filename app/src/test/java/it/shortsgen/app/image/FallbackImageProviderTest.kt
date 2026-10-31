package it.shortsgen.app.image

import it.shortsgen.app.core.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FallbackImageProviderTest {
    private class Fake(override val name: String, val error: Exception? = null) : ImageProvider {
        var calls = 0
        override suspend fun generate(prompt: String): ByteArray {
            calls++
            error?.let { throw it }
            return name.toByteArray()
        }
    }

    @Test
    fun switchesToSecondaryOnInvalidCredentials() = runTest {
        val primary = Fake("cf", ApiException("Cloudflare", 401, "{}"))
        val secondary = Fake("gemini")
        var reason: String? = null
        val provider = FallbackImageProvider(primary, secondary) { reason = it }
        assertArrayEquals("gemini".toByteArray(), provider.generate("a"))
        assertArrayEquals("gemini".toByteArray(), provider.generate("b"))
        assertEquals(1, primary.calls) // dopo il primo errore non lo riprova più
        assertEquals(2, secondary.calls)
        assertEquals("gemini", provider.name)
        assertEquals(true, reason!!.contains("401"))
    }

    @Test
    fun networkErrorsAreNotSwitched() = runTest {
        val provider = FallbackImageProvider(Fake("cf", IOException("rete")), Fake("gemini"))
        try {
            provider.generate("a")
            fail()
        } catch (e: IOException) {
            // corretto: gli errori di rete si gestiscono con i nuovi tentativi
        }
    }
}
