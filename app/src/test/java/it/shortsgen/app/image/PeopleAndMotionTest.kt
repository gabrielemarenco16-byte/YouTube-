package it.shortsgen.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleAndMotionTest {
    @Test
    fun peopleAreDetectedInDescriptions() {
        assertTrue(PeopleFilter.showsPeople("woman swimming with jellyfish"))
        assertTrue(PeopleFilter.showsPeople("people, beach, summer"))
        assertFalse(PeopleFilter.showsPeople("jellyfish, sea, ocean, underwater"))
        assertFalse(PeopleFilter.allowed("a man looking at the sea", allowPeople = false))
        assertTrue(PeopleFilter.allowed("a man looking at the sea", allowPeople = true))
    }

    @Test
    fun scriptMentioningPeopleAllowsThem() {
        assertTrue(PeopleFilter.scriptMentionsPeople("Ancient Egyptian workers built the pyramids with simple tools"))
        assertFalse(PeopleFilter.scriptMentionsPeople("Jellyfish have no brain, no heart and no bones."))
    }

    @Test
    fun pexelsDescriptionFromUrlAndPeopleFilter() {
        assertEquals("jellyfish in the sea", PexelsApi.describeFromUrl("https://www.pexels.com/video/jellyfish-in-the-sea-1234567/"))
        val json = """
            {"videos":[
              {"id":1,"duration":20,"url":"https://www.pexels.com/video/woman-diving-with-jellyfish-111/",
               "video_files":[{"link":"https://v/1.mp4","width":1080,"height":1920,"file_type":"video/mp4"}]},
              {"id":2,"duration":20,"url":"https://www.pexels.com/video/jellyfish-glowing-222/",
               "video_files":[{"link":"https://v/2.mp4","width":1080,"height":1920,"file_type":"video/mp4"}]}
            ]}
        """.trimIndent()
        val videos = PexelsApi.parseVideos(json)
        assertEquals(2L, PexelsApi.chooseVideo(videos, 5_000, emptySet(), allowPeople = false)!!.first.id)
        assertEquals(1L, PexelsApi.chooseVideo(videos, 5_000, emptySet(), allowPeople = true)!!.first.id)
        assertEquals(2, PexelsApi.chooseVideos(videos, 5_000, emptySet(), allowPeople = true, max = 3).size)
    }

    @Test
    fun pixabayTagsFilterPeople() {
        val json = """{"hits":[
            {"id":5,"duration":20,"tags":"girl, jellyfish, aquarium","videos":{"large":{"url":"https://c/5.mp4","width":1080,"height":1920}}},
            {"id":6,"duration":20,"tags":"jellyfish, ocean","videos":{"large":{"url":"https://c/6.mp4","width":1080,"height":1920}}}
        ]}"""
        assertEquals(6L, PixabayApi.chooseVideo(PixabayApi.parseVideos(json), 5_000, emptySet(), allowPeople = false)!!.first.id)
    }

    @Test
    fun motionScoreGrowsWithChange() {
        val black = IntArray(100) { 0xFF000000.toInt() }
        val white = IntArray(100) { 0xFFFFFFFF.toInt() }
        assertEquals(0.0, MotionMath.score(listOf(black, black, black)), 1e-9)
        assertTrue(MotionMath.score(listOf(black, white, black)) > 200)
    }
}
