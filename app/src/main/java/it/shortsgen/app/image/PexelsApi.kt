package it.shortsgen.app.image

import it.shortsgen.app.core.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Lettura delle risposte Pexels e scelta dei file (logica pura, testabile). */
object PexelsApi {
    data class VideoFile(val link: String, val width: Int, val height: Int, val fileType: String)
    /** [description]: testo ricavato dall'indirizzo della pagina (es. "jellyfish swimming in the ocean"). */
    data class Video(val id: Long, val durationSec: Int, val files: List<VideoFile>, val description: String = "")
    data class Photo(val id: Long, val url: String, val description: String = "")

    /** "https://www.pexels.com/video/jellyfish-in-the-sea-1234567/" → "jellyfish in the sea" */
    fun describeFromUrl(url: String?): String =
        url.orEmpty().trimEnd('/').substringAfterLast('/').replace(Regex("-?\\d+$"), "").replace('-', ' ').trim()

    fun videoId(id: Long) = "pexels-video-$id"
    fun photoId(id: Long) = "pexels-photo-$id"

    fun parseVideos(json: String): List<Video> {
        val root = Http.json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val videos = root["videos"] as? JsonArray ?: return emptyList()
        return videos.mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val duration = (o["duration"] as? JsonPrimitive)?.intOrNull ?: 0
            val files = (o["video_files"] as? JsonArray).orEmpty().mapNotNull { f ->
                val fo = f as? JsonObject ?: return@mapNotNull null
                val link = (fo["link"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                VideoFile(
                    link = link,
                    width = (fo["width"] as? JsonPrimitive)?.intOrNull ?: 0,
                    height = (fo["height"] as? JsonPrimitive)?.intOrNull ?: 0,
                    fileType = (fo["file_type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }
            Video(id, duration, files, describeFromUrl((o["url"] as? JsonPrimitive)?.contentOrNull))
        }
    }

    fun parsePhotos(json: String): List<Photo> {
        val root = Http.json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val photos = root["photos"] as? JsonArray ?: return emptyList()
        return photos.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val src = o["src"] as? JsonObject ?: return@mapNotNull null
            val original = (src["original"] as? JsonPrimitive)?.contentOrNull
            val url = if (original != null) {
                // Pexels ritaglia lato server: già 9:16 e alla risoluzione giusta
                "$original?auto=compress&cs=tinysrgb&fit=crop&w=1080&h=1920"
            } else {
                (src["portrait"] as? JsonPrimitive)?.contentOrNull ?: (src["large2x"] as? JsonPrimitive)?.contentOrNull
            } ?: return@mapNotNull null
            val alt = (o["alt"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            Photo(id, url, alt.ifEmpty { describeFromUrl((o["url"] as? JsonPrimitive)?.contentOrNull) })
        }
    }

    /** Il file MP4 verticale con altezza più vicina a 1920 (tra 960 e 2160). */
    fun chooseFile(files: List<VideoFile>): VideoFile? =
        files.filter { it.fileType.contains("mp4") && it.height >= it.width && it.height in 960..2160 }
            .minByOrNull { kotlin.math.abs(it.height - 1920) }

    /** Primo video non ancora usato, lungo abbastanza e con un file adatto. */
    /** Primo video non ancora usato, lungo abbastanza, senza persone (se vietate) e con un file adatto. */
    fun chooseVideo(videos: List<Video>, minDurationMs: Long, usedIds: Set<String>, allowPeople: Boolean = true): Pair<Video, VideoFile>? =
        chooseVideos(videos, minDurationMs, usedIds, allowPeople, 1).firstOrNull()

    /** Fino a [max] video adatti, nell'ordine dei risultati. */
    fun chooseVideos(videos: List<Video>, minDurationMs: Long, usedIds: Set<String>, allowPeople: Boolean, max: Int): List<Pair<Video, VideoFile>> =
        videos.asSequence()
            .filter { videoId(it.id) !in usedIds }
            .filter { it.durationSec * 1000L >= minDurationMs }
            .filter { PeopleFilter.allowed(it.description, allowPeople) }
            .mapNotNull { v -> chooseFile(v.files)?.let { v to it } }
            .take(max)
            .toList()
}
