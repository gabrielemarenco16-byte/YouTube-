package it.shortsgen.app.image

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PixabayProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private var videosJson = ""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/videos/") -> MockResponse().setBody(videosJson)
                    path.startsWith("/api/") -> MockResponse().setBody("""{"hits":[]}""")
                    path.startsWith("/v/") -> MockResponse().setBody("fake-mp4-bytes")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun video(id: Int, tags: String) =
        """{"id":$id,"duration":20,"tags":"$tags","videos":{"large":{"url":"${server.url("/v/$id.mp4")}","width":1080,"height":1920}}}"""

    private fun provider() = PixabayProvider("12345678-abcdef0123456789abcdef", OkHttpClient(), server.url("/").toString().trimEnd('/'))

    private val relevance = RelevanceFilter.build("jellyfish", emptyList(), emptyList(), "marine")

    private fun request(previouslyUsed: Set<String>) = MediaRequest(
        sceneIndex = 0, queries = listOf("jellyfish"), prompt = "p", minDurationMs = 5_000,
        allowPeople = false, subject = "jellyfish", relevance = relevance, previouslyUsed = previouslyUsed,
    )

    @Test
    fun prefersClipsNeverUsedBefore() = runTest {
        videosJson = """{"hits":[${video(1, "jellyfish, sea")},${video(2, "jellyfish, ocean")}]}"""
        val media = provider().fetch(request(previouslyUsed = setOf("pixabay-video-1")), tmp.root, emptySet())
        assertEquals("pixabay-video-2", media.sourceId)
        assertFalse(media.previouslyUsed)
    }

    @Test
    fun reusesAnOldClipOnlyIfThereIsNoAlternative() = runTest {
        videosJson = """{"hits":[${video(1, "jellyfish, sea")},${video(3, "moss, garden")}]}"""
        val media = provider().fetch(request(previouslyUsed = setOf("pixabay-video-1")), tmp.root, emptySet())
        assertEquals("pixabay-video-1", media.sourceId) // la 3 è irrilevante (tag terrestri)
        assertTrue(media.previouslyUsed)
    }
}
