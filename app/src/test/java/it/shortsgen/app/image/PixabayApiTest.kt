package it.shortsgen.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixabayApiTest {
    private val videosJson = """
        {"total":3,"hits":[
          {"id":10,"duration":20,"videos":{
             "large":{"url":"https://cdn/10_large.mp4","width":1920,"height":1080},
             "medium":{"url":"https://cdn/10_medium.mp4","width":1280,"height":720}}},
          {"id":11,"duration":15,"videos":{
             "large":{"url":"https://cdn/11_large.mp4","width":1080,"height":1920},
             "small":{"url":"https://cdn/11_small.mp4","width":540,"height":960}}},
          {"id":12,"duration":3,"videos":{"large":{"url":"https://cdn/12.mp4","width":1080,"height":1920}}}
        ]}
    """.trimIndent()

    @Test
    fun recognisesPixabayKeys() {
        assertTrue(PixabayApi.looksLikeKey("58339601-96e40fe5c5b5ef3f2a1b2c3d4"))
        assertFalse(PixabayApi.looksLikeKey("563492ad6f91700001000001abcdef0123456789abcdef0123456789"))
        assertFalse(PixabayApi.looksLikeKey(""))
    }

    @Test
    fun prefersVerticalVideosLongEnough() {
        val (video, file) = PixabayApi.chooseVideo(PixabayApi.parseVideos(videosJson), 7_000, emptySet())!!
        assertEquals(11L, video.id)
        assertEquals("https://cdn/11_large.mp4", file.url)
    }

    @Test
    fun fallsBackToCroppableLandscapeAndNeverReuses() {
        val (video, file) = PixabayApi.chooseVideo(PixabayApi.parseVideos(videosJson), 7_000, setOf(PixabayApi.videoId(11)))!!
        assertEquals(10L, video.id)
        assertEquals(1080, file.height)
        assertNull(PixabayApi.chooseVideo(PixabayApi.parseVideos(videosJson), 30_000, emptySet()))
    }

    @Test
    fun parsesPhotos() {
        val json = """{"hits":[{"id":5,"largeImageURL":"https://cdn/5_1280.jpg","imageWidth":3000,"imageHeight":4500}]}"""
        assertEquals("https://cdn/5_1280.jpg", PixabayApi.parsePhotos(json).single().url)
    }
}
