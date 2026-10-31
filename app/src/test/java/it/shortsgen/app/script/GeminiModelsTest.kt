package it.shortsgen.app.script

import it.shortsgen.app.core.ApiException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelsTest {

    private val listJson = """
        {"models":[
          {"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]},
          {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.0-pro","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.0-flash","supportedGenerationMethods":["generateContent","countTokens"]},
          {"name":"models/gemini-3.0-flash-lite","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.5-flash-preview","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.0-flash-image","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-2.5-flash-image","supportedGenerationMethods":["generateContent"]}
        ]}
    """.trimIndent()

    @Test
    fun parsesOnlyGenerateContentModels() {
        val models = GeminiModels.parseModelList(listJson)
        assertFalse(models.contains("embedding-001"))
        assertTrue(models.contains("gemini-3.0-flash"))
    }

    @Test
    fun picksNewestStableFlashForText() {
        assertEquals("gemini-3.0-flash", GeminiModels.pick(GeminiModels.parseModelList(listJson), GeminiModels.Kind.TEXT))
    }

    @Test
    fun picksNewestImageModel() {
        assertEquals("gemini-3.0-flash-image", GeminiModels.pick(GeminiModels.parseModelList(listJson), GeminiModels.Kind.IMAGE))
    }

    @Test
    fun noImageModelGivesNull() {
        assertNull(GeminiModels.pick(listOf("gemini-3.0-flash"), GeminiModels.Kind.IMAGE))
    }

    @Test
    fun detectsModelNotFound() {
        assertTrue(GeminiModels.isModelNotFound(ApiException("Gemini", 404, "")))
        assertTrue(GeminiModels.isModelNotFound(ApiException("Gemini", 400, "model is not found for API version")))
        assertFalse(GeminiModels.isModelNotFound(ApiException("Gemini", 400, "invalid argument")))
        assertFalse(GeminiModels.isModelNotFound(ApiException("Gemini", 403, "")))
    }

    @Test
    fun textProviderSwitchesToAvailableModelAfter404() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"models/gemini-2.5-flash is not found"}}"""))
            server.enqueue(MockResponse().setBody(listJson))
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"{}"}]}}]}"""))
            var saved: String? = null
            val provider = GeminiTextProvider(
                "K", "gemini-2.5-flash", OkHttpClient(), server.url("/").toString().trimEnd('/'),
                onModelChanged = { saved = it },
            )
            assertEquals("{}", provider.complete("x"))
            assertEquals("gemini-3.0-flash", saved)
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", server.takeRequest().path)
            assertEquals("/v1beta/models?pageSize=1000", server.takeRequest().path)
            assertEquals("/v1beta/models/gemini-3.0-flash:generateContent", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun triesNextCandidateWhenFirstReplacementAlso404() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            server.enqueue(MockResponse().setBody(listJson))
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}")) // gemini-3.0-flash
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))
            var saved: String? = null
            val provider = GeminiTextProvider(
                "K", "gemini-flash-latest", OkHttpClient(), server.url("/").toString().trimEnd('/'),
                onModelChanged = { saved = it },
            )
            assertEquals("ok", provider.complete("x"))
            assertEquals("gemini-3.0-flash-lite", saved)
        } finally {
            server.shutdown()
        }
    }
}
