package it.shortsgen.app.script

import it.shortsgen.app.core.ApiException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TextProviderTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base() = server.url("/").toString().trimEnd('/')

    @Test
    fun geminiRequestAndResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"{\"a\":"},{"text":"1}"}]}}]}"""
            )
        )
        val provider = GeminiTextProvider("KEY", "gemini-test", client, base())
        assertEquals("{\"a\":1}", provider.complete("hello"))
        val req = server.takeRequest()
        assertEquals("/v1beta/models/gemini-test:generateContent", req.path)
        assertEquals("KEY", req.getHeader("x-goog-api-key"))
        assertTrue(req.body.readUtf8().contains("\"responseMimeType\":\"application/json\""))
    }

    @Test
    fun groqRequestAndResponse() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"b\":2}"}}]}"""))
        val provider = GroqTextProvider("GK", "llama-test", client, base())
        assertEquals("{\"b\":2}", provider.complete("hello"))
        val req = server.takeRequest()
        assertEquals("/openai/v1/chat/completions", req.path)
        assertEquals("Bearer GK", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("json_object"))
    }

    @Test
    fun httpErrorBecomesApiException() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val provider = GroqTextProvider("GK", "m", client, base())
        try {
            provider.complete("x")
            fail("doveva lanciare")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
    }
}
