package it.shortsgen.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PexelsApiTest {
    private val videosJson = """
        {"videos":[
          {"id":1,"duration":4,"video_files":[{"link":"https://v/1.mp4","width":1080,"height":1920,"file_type":"video/mp4"}]},
          {"id":2,"duration":15,"video_files":[
             {"link":"https://v/2-4k.mp4","width":2160,"height":3840,"file_type":"video/mp4"},
             {"link":"https://v/2-hd.mp4","width":1080,"height":1920,"file_type":"video/mp4"},
             {"link":"https://v/2-sd.mp4","width":540,"height":960,"file_type":"video/mp4"}]},
          {"id":3,"duration":20,"video_files":[{"link":"https://v/3.mp4","width":1920,"height":1080,"file_type":"video/mp4"}]},
          {"id":4,"duration":30,"video_files":[{"link":"https://v/4.mp4","width":720,"height":1280,"file_type":"video/mp4"}]}
        ]}
    """.trimIndent()

    @Test
    fun parsesVideos() {
        val videos = PexelsApi.parseVideos(videosJson)
        assertEquals(4, videos.size)
        assertEquals(3, videos[1].files.size)
    }

    @Test
    fun choosesLongEnoughVerticalClipWithFullHdFile() {
        val (video, file) = PexelsApi.chooseVideo(PexelsApi.parseVideos(videosJson), 7_000, emptySet())!!
        assertEquals(2L, video.id) // il video 1 è troppo corto
        assertEquals("https://v/2-hd.mp4", file.link)
    }

    @Test
    fun neverReusesTheSameVideo() {
        val (video, _) = PexelsApi.chooseVideo(PexelsApi.parseVideos(videosJson), 7_000, setOf(PexelsApi.videoId(2)))!!
        assertEquals(4L, video.id) // il 3 è orizzontale, quindi il 4
    }

    @Test
    fun noSuitableVideo() {
        assertNull(PexelsApi.chooseVideo(PexelsApi.parseVideos(videosJson), 60_000, emptySet()))
    }

    @Test
    fun photoUrlIsCroppedTo1080x1920() {
        val json = """{"photos":[{"id":9,"src":{"original":"https://p/9.jpeg","portrait":"https://p/9-p.jpeg"}}]}"""
        val photo = PexelsApi.parsePhotos(json).single()
        assertEquals(9L, photo.id)
        assertTrue(photo.url.startsWith("https://p/9.jpeg?"))
        assertTrue(photo.url.contains("w=1080") && photo.url.contains("h=1920") && photo.url.contains("fit=crop"))
    }
}
