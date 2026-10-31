package it.shortsgen.app.image

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.Base64

class ImageResponseTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    private val b64 = Base64.getEncoder().encodeToString(png)

    @Test
    fun geminiInlineData() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"here"},{"inlineData":{"mimeType":"image/png","data":"$b64"}}]}}]}"""
        assertArrayEquals(png, GeminiImageProvider.extractImage(json))
    }

    @Test
    fun geminiSnakeCase() {
        val json = """{"candidates":[{"content":{"parts":[{"inline_data":{"mime_type":"image/png","data":"$b64"}}]}}]}"""
        assertArrayEquals(png, GeminiImageProvider.extractImage(json))
    }

    @Test(expected = Exception::class)
    fun geminiWithoutImageFails() {
        GeminiImageProvider.extractImage("""{"candidates":[{"content":{"parts":[{"text":"no"}]}}]}""")
    }

    @Test
    fun cloudflareJson() {
        val body = """{"result":{"image":"$b64"},"success":true}""".toByteArray()
        assertArrayEquals(png, CloudflareImageProvider.decodeResponse(body, "application/json"))
    }

    @Test
    fun cloudflareBinary() {
        assertArrayEquals(png, CloudflareImageProvider.decodeResponse(png, "image/png"))
    }
}
