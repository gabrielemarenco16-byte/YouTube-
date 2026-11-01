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
    fun failedSceneReusesAnotherSceneClipWithClearLog() = runTest {
        val logs = mutableListOf<String>()
        val primary = Fake("Pexels", { _: Int -> GenerationException("Pexels: nessun video trovato") })
        val secondary = Fake("Cloudflare", { _: Int -> MissingKeyException("CF_ACCOUNT_ID / CF_API_TOKEN", "Credenziali Cloudflare mancanti") })
        val result = collector(primary, secondary, logs = logs).collect(requests(2), tmp.root)
        // la scena 1 non trova nulla: riusa la clip della scena 2, nessuna scheda grafica
        assertEquals(0, result.placeholderCount)
        assertEquals(1, result.media[0].reusedFromScene)
        assertTrue(result.warnings.any { it.contains("riusato un tratto della clip della scena 2") })
        assertTrue(logs.any { it.contains("Scena 1: nessuna clip del soggetto") })
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

    @Test
    fun sceneWithoutSubjectClipReusesAnotherSceneClipNotTheCard() = runTest {
        val none = { _: Int -> GenerationException("nessuna clip del soggetto") }
        // scena 0 ok, scena 1 niente, scena 2 ok
        val provider = Fake("Pixabay", { _: Int -> "clip-a" }, none, { _: Int -> "clip-b" })
        val requests = List(3) { MediaRequest(it, listOf("jellyfish q$it"), "p", 4_000) }
        val result = collector(provider).collect(requests, tmp.root)
        assertEquals(0, result.placeholderCount)
        val reused = result.media[1]
        assertTrue(reused.reusedFromScene in listOf(0, 2))
        assertTrue(reused.branch.startsWith("riuso clip della scena"))
        assertTrue(result.sceneLog[1].contains("riuso clip della scena"))
    }

    @Test
    fun reusedClipStartsAfterTheUsedPart() {
        val donor = SceneMedia(MediaKind.VIDEO, "f", "Pixabay", "x", durationMs = 20_000)
        assertEquals(5_000, MediaCollector.reuseStart(donor, donorUsedMs = 5_000, nextFree = null, needMs = 6_000))
        // non c'è spazio dopo: usa la parte finale
        assertEquals(19_500 - 6_000, MediaCollector.reuseStart(donor, donorUsedMs = 15_000, nextFree = null, needMs = 6_000))
        // clip troppo corta: dall'inizio
        assertEquals(0, MediaCollector.reuseStart(donor.copy(durationMs = 4_000), 3_000, null, 6_000))
    }

    @Test
    fun hookKeepsTheMostDynamicCandidate() = runTest {
        val provider = object : ImageProvider {
            override val name = "Pixabay"
            override suspend fun fetch(request: MediaRequest, dir: File, usedIds: Set<String>) = error("non usato")
            override suspend fun fetchCandidates(request: MediaRequest, dir: File, usedIds: Set<String>) =
                listOf("calm", "wild", "medium").map {
                    SceneMedia(MediaKind.VIDEO, File(dir, "$it.mp4").apply { writeText("x") }.path, name, it, query = "jellyfish")
                }
        }
        val scores = mapOf("calm" to 2.0, "wild" to 30.0, "medium" to 10.0)
        val c = MediaCollector(
            providers = listOf(provider),
            placeholder = { r, d -> error("non usato") },
            pauseBetweenScenesMs = 0,
            motionScore = { scores.getValue(it.sourceId) },
        )
        val result = c.collect(listOf(MediaRequest(0, listOf("jellyfish"), "p", 4_000, candidates = 3)), tmp.root)
        assertEquals("wild", result.media.single().sourceId)
        assertEquals(30.0, result.media.single().motion, 1e-9)
        assertFalse(File(tmp.root, "calm.mp4").exists())
    }
}
