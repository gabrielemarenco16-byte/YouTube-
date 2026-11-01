package it.shortsgen.app.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RetryTest {
    @Test
    fun succeedsAfterTransientFailures() = runTest {
        var calls = 0
        val result = retry(times = 3, initialDelayMs = 10) {
            calls++
            if (calls < 3) throw IOException("rete")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun doesNotRetryInvalidKey() = runTest {
        var calls = 0
        try {
            retry(times = 3, initialDelayMs = 10) {
                calls++
                throw ApiException("Gemini", 401, "")
            }
            fail()
        } catch (e: ApiException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun givesUpAfterMaxAttempts() = runTest {
        var calls = 0
        try {
            retry(times = 2, initialDelayMs = 10) {
                calls++
                throw ApiException("Groq", 503, "")
            }
            fail()
        } catch (e: ApiException) {
            assertEquals(2, calls)
        }
    }
}
