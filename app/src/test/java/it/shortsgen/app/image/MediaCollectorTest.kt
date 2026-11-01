package it.shortsgen.app.image

import it.shortsgen.app.core.ApiException
import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.MissingKeyException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MediaCollectorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun requests(n: Int) = List(n) { MediaRequest(it, listOf("q$it"), "prompt $it", 5_000) }

    /** Fornitore finto: per ogni chiamata esegue la prossima azione della lista. */
    private class Fake(override val name: String, vararg actions: (Int) -> Any) : ImageProvider {
        val queue = ArrayDeque(actions.toList())
        var calls = 0
        override suspend fun fetch(request: MediaRequest, dir: File, usedIds: Set<String>): SceneMedia {
            calls++
            val action = queue.removeFirstOrNull() ?: { _: Int -> "$name-${request.sceneIndex}" }
            return when (val r = action(request.sceneIndex)) {
                is Exception -> throw r
                else -> {
                    val id = r.toString()
                    check(id !in usedIds) { "file riusato" }
                    SceneMedia(MediaKind.VIDEO, File(dir, "$id.mp4").apply { writeText("x") }.path, name, id,
                        query = request.queries.first(), detail = "video 1080x1920")
                }
            }
        }
    }

    private fun collector(vararg providers: ImageProvider, logs: MutableList<String> = mutableListOf()) = MediaCollector(
        providers = providers.toList(),
        placeholder = { r, dir -> SceneMedia(MediaKind.IMAGE, File(dir, "card${r.sceneIndex}.jpg").path, "scheda grafica", "card-${r.sceneIndex}", true) },
        retryDelayMs = 1,
        pauseBetweenScenesMs = 0,
        log = { logs += it },
    )

    @Test
    fun allScenesFromPrimary() = runTest {
        val result = collector(Fake("Pexels")).collect(requests(3), tmp.root)
        assertEquals(listOf("Pexels-0", "Pexels-1", "Pexels-2"), result.media.map { it.sourceId })
        assertEquals(0, result.placeholderCount)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun retriesThreeTimesThenUsesSecondary() = runTest {
        val io = { _: Int -> IOException("rete") }
        val primary = Fake("Pexels", io, io, io)
        val secondary = Fake("Cloudflare")
        val result = collector(primary, secondary).collect(requests(2), tmp.root)
        assertEquals("Cloudflare", result.media[0].source)
        assertEquals("Pexels", result.media[1].source) // la scena successiva riprova il principale
        assertEquals(4, primary.calls)
    }

    @Test
    fun invalidKeyDisablesProviderForLaterScenes() = runTest {
        val primary = Fake("Pexels", { _: Int -> ApiException("Pexels", 401, "{}") })
        val secondary = Fake("Cloudflare")
        val result = collector(primary, secondary).collect(requests(3), tmp.root)
        assertEquals(1, primary.calls)
        assertTrue(result.media.all { it.source == "Cloudflare" })
        assertTrue(result.warnings.single().contains("PEXELS_API_KEY"))
    }

    @Test
    fun placeholderOnlyWhenEverythingFailsWithClearWarning() = runTest {
        val logs = mutableListOf<String>()
        val primary = Fake("Pexels", { _: Int -> GenerationException("Pexels: nessun video trovato") })
        val secondary = Fake("Cloudflare", { _: Int -> MissingKeyException("CF_ACCOUNT_ID / CF_API_TOKEN", "Credenziali Cloudflare mancanti") })
        val result = collector(primary, secondary, logs = logs).collect(requests(2), tmp.root)
        assertTrue(result.media[0].placeholder)
        assertFalse(result.media[1].placeholder)
        assertTrue(result.warnings.any { it.contains("scena 1 senza clip né foto") && it.contains("scheda grafica") })
        assertTrue(logs.any { it.contains("ATTENZIONE") })
    }

    @Test
    fun reusesSavedMediaOnRetry() = runTest {
        val saved = SceneMedia(MediaKind.VIDEO, File(tmp.root, "old.mp4").apply { writeText("x") }.path, "Pexels", "old")
        val provider = Fake("Pexels")
        val result = collector(provider).collect(requests(2), tmp.root, existing = listOf(saved, null))
        assertEquals("old", result.media[0].sourceId)
        assertEquals(1, provider.calls)
    }

    @Test
    fun everyPlaceholderWarningNamesTheRealCause() = runTest {
        val pexels = Fake("Pexels", { _: Int -> MissingKeyException("PEXELS_API_KEY", "Chiave Pexels mancante: inserisci PEXELS_API_KEY") })
        val result = collector(pexels).collect(requests(3), tmp.root)
        assertEquals(3, result.placeholderCount)
        val sceneWarnings = result.warnings.filter { it.startsWith("ATTENZIONE") }
        assertEquals(3, sceneWarnings.size)
        assertTrue(sceneWarnings.all { it.contains("PEXELS_API_KEY") })
    }

    @Test
    fun logsQueryAndFileForEveryScene() = runTest {
        val infos = mutableListOf<String>()
        val c = MediaCollector(
            providers = listOf(Fake("Pixabay")),
            placeholder = { r, dir -> SceneMedia(MediaKind.IMAGE, File(dir, "card${r.sceneIndex}.jpg").path, "scheda grafica", "card-${r.sceneIndex}", true) },
            pauseBetweenScenesMs = 0,
            info = { infos += it },
        )
        val result = c.collect(requests(2), tmp.root)
        assertEquals(2, result.sceneLog.size)
        assertEquals(result.sceneLog, infos)
        assertTrue(infos[0], infos[0].startsWith("Scena 1 | ramo: clip – query della scena | query usata: \"q0\" | Pixabay Pixabay-0"))
        assertTrue(infos[0].contains("file: Pixabay-0.mp4"))
    }

    @Test
    fun branchTellsSceneOrThemeQueryAndCard() {
        val request = MediaRequest(0, listOf("seed pod", "seed close up", "tree", "forest"), "p", 5_000, sceneQueryCount = 2)
        fun media(q: String, kind: MediaKind = MediaKind.VIDEO) = SceneMedia(kind, "f", "Pixabay", "x", query = q)
        assertEquals("clip – query della scena", MediaCollector.branchOf(request, media("seed close up")))
        assertEquals("clip – query del tema", MediaCollector.branchOf(request, media("forest")))
        assertEquals("foto – query del tema", MediaCollector.branchOf(request, media("tree", MediaKind.IMAGE)))
        assertEquals(MediaCollector.BRANCH_CARD, MediaCollector.branchOf(request, SceneMedia(MediaKind.IMAGE, "f", "scheda grafica", "c", placeholder = true)))
    }

    @Test
    fun cardIsUsedOnlyWhenEverythingFailsAndIsLogged() = runTest {
        val infos = mutableListOf<String>()
        val none = { _: Int -> GenerationException("nessun risultato") }
        val c = MediaCollector(
            providers = listOf(Fake("Pixabay", none)),
            placeholder = { r, dir -> SceneMedia(MediaKind.IMAGE, File(dir, "card.jpg").path, "scheda grafica", "card-0", true) },
            pauseBetweenScenesMs = 0,
            info = { infos += it },
        )
        val result = c.collect(listOf(MediaRequest(0, listOf("seed pod", "tree"), "p", 5_000, sceneQueryCount = 1, keyword = "seed pod")), tmp.root)
        assertTrue(result.media.single().placeholder)
        assertTrue(infos.single(), infos.single().contains("ramo: scheda grafica"))
        assertTrue(infos.single().contains("parola: \"seed pod\""))
    }

    @Test
    fun replaceFindsADifferentFileAndPrefersSameService() = runTest {
        val pexels = Fake("Pexels")
        val pixabay = Fake("Pixabay", { _: Int -> "pixabay-new" })
        val c = collector(pexels, pixabay)
        val request = MediaRequest(2, listOf("coral reef", "ocean"), "p", 5_000, sceneQueryCount = 1)
        val (media, line) = c.replace(request, tmp.root, excludedIds = setOf("pixabay-old"), preferredSource = "Pixabay")!!
        assertEquals("pixabay-new", media.sourceId)
        assertEquals(0, pexels.calls)
        assertTrue(line, line.contains("ramo: cambio clip – clip – query della scena"))
    }

    @Test
    fun replaceReturnsNullWhenNothingElseIsFound() = runTest {
        val none = { _: Int -> GenerationException("nessun altro risultato") }
        val c = collector(Fake("Pixabay", none, none, none))
        assertEquals(null, c.replace(MediaRequest(0, listOf("x"), "p", 1, 1), tmp.root, emptySet(), "Pixabay"))
    }
}
