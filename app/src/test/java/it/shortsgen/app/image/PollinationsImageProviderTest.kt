package it.shortsgen.app.image

import it.shortsgen.app.core.ApiException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PollinationsImageProviderTest {
    private lateinit var server: MockWebServer
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
    private var now = 1_000_000L
    private val waits = mutableListOf<Long>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun image() = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))

    private fun provider() = PollinationsImageProvider(
        client = OkHttpClient(),
        baseUrl = server.url("/").toString().trimEnd('/'),
        clock = { now },
        wait = { ms -> waits += ms; now += ms },
    )

    @Test
    fun requestsImageWithEncodedPrompt() = runTest {
        server.enqueue(image())
        assertArrayEquals(jpeg, provider().generate("a red octopus, 9:16"))
        val path = server.takeRequest().path!!
        assertTrue(path, path.startsWith("/prompt/a%20red%20octopus,%209:16?"))
        assertTrue(path.contains("nologo=true"))
    }

    @Test
    fun waitsBetweenImagesForFreeLimit() = runTest {
        server.enqueue(image())
        server.enqueue(image())
        val p = provider()
        p.generate("one")
        now += 5_000 // l'immagine successiva è chiesta dopo 5 secondi
        p.generate("two")
        assertEquals(listOf(PollinationsImageProvider.DEFAULT_INTERVAL_MS - 5_000), waits)
    }

    @Test
    fun honorsRetryAfterOn402() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(402)
                .setBody("""{"error":"Free limit reached: 1 image every 45 seconds per IP. Retry after 41s"}""")
        )
        server.enqueue(image())
        assertArrayEquals(jpeg, provider().generate("x"))
        assertEquals(listOf(43_000L), waits)
    }

    @Test
    fun retryAfterParsing() {
        assertEquals(12_000L, PollinationsImageProvider.retryAfterMs(ApiException("P", 429, "Retry after 10s")))
        assertEquals(47_000L, PollinationsImageProvider.retryAfterMs(ApiException("P", 402, "1 image every 45 seconds")))
        assertNull(PollinationsImageProvider.retryAfterMs(ApiException("P", 500, "Retry after 10s")))
    }
}
