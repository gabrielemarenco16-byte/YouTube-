package it.shortsgen.app.image

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollinationsImageProviderTest {
    @Test
    fun requestsImageWithEncodedPrompt() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)))
            val provider = PollinationsImageProvider(OkHttpClient(), server.url("/").toString().trimEnd('/'))
            assertArrayEquals(jpeg, provider.generate("a red octopus, 9:16"))
            val path = server.takeRequest().path!!
            assertTrue(path, path.startsWith("/prompt/a%20red%20octopus,%209:16?"))
            assertTrue(path.contains("nologo=true"))
        } finally {
            server.shutdown()
        }
    }
}
