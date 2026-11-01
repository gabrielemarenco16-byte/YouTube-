package it.shortsgen.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipHistoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun remembersStockClipsAcrossInstances() {
        val file = File(tmp.root, ClipHistory.FILE_NAME)
        ClipHistory(file).add(listOf("pixabay-video-1", "pexels-video-2", "card-0", "cloudflare-1-99"))
        val ids = ClipHistory(file).ids()
        assertEquals(setOf("pixabay-video-1", "pexels-video-2"), ids) // schede e immagini AI escluse
    }

    @Test
    fun keepsOnlyTheMostRecent() {
        val h = ClipHistory(File(tmp.root, "h.json"), maxEntries = 3)
        h.add(listOf("pixabay-video-1", "pixabay-video-2"), now = 1)
        h.add(listOf("pixabay-video-3", "pixabay-video-4"), now = 2)
        assertEquals(setOf("pixabay-video-2", "pixabay-video-3", "pixabay-video-4"), h.ids())
    }

    @Test
    fun missingOrBrokenFileMeansEmptyHistory() {
        val file = File(tmp.root, "broken.json").apply { writeText("{not json") }
        assertTrue(ClipHistory(file).ids().isEmpty())
    }
}
