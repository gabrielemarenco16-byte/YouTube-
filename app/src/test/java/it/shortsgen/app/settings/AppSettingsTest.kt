package it.shortsgen.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    private val pixabayKey = "58339601-96e40fe5c5b5ef3f2a1b2c3d4"

    @Test
    fun pixabayKeyInPexelsFieldIsUsedForPixabay() {
        val s = AppSettings(geminiKey = "g", pexelsKey = pixabayKey)
        assertTrue(s.pixabayKeyInPexelsField)
        assertEquals("", s.effectivePexelsKey)
        assertEquals(pixabayKey, s.effectivePixabayKey)
        assertEquals(listOf(ImageProviderType.PIXABAY), s.imageSources())
        assertTrue(s.missing().isEmpty())
    }

    @Test
    fun selectedServiceComesFirstThenOthersConfigured() {
        val s = AppSettings(
            imageProvider = ImageProviderType.CLOUDFLARE,
            pexelsKey = "abc", pixabayKey = pixabayKey, cloudflareAccountId = "id", cloudflareToken = "t",
        )
        assertEquals(listOf(ImageProviderType.CLOUDFLARE, ImageProviderType.PEXELS, ImageProviderType.PIXABAY), s.imageSources())
    }

    @Test
    fun missingKeysAreNamed() {
        val s = AppSettings()
        assertEquals(listOf(ImageProviderType.PEXELS), s.imageSources())
        assertTrue(s.missing().any { it.startsWith("GEMINI_API_KEY") })
        assertTrue(s.missing().any { it.startsWith("PEXELS_API_KEY") })
    }
}
