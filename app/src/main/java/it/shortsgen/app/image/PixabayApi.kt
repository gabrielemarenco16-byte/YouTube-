package it.shortsgen.app.image

import it.shortsgen.app.core.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Lettura delle risposte Pixabay e scelta dei file (logica pura, testabile). */
object PixabayApi {
    data class VideoFile(val url: String, val width: Int, val height: Int)
    /** [description]: i tag di Pixabay (es. "jellyfish, sea, ocean"). */
    data class Video(val id: Long, val durationSec: Int, val files: List<VideoFile>, val description: String = "")
    data class Photo(val id: Long, val url: String, val width: Int, val height: Int, val description: String = "")

    fun videoId(id: Long) = "pixabay-video-$id"
    fun photoId(id: Long) = "pixabay-photo-$id"

    /** Chiave Pixabay: "12345678-" seguito da caratteri esadecimali. */
    fun looksLikeKey(key: String): Boolean = Regex("^\\d{5,12}-[0-9a-fA-F]{16,48}$").matches(key.trim())

    fun parseVideos(json: String): List<Video> {
        val root = Http.json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val hits = root["hits"] as? JsonArray ?: return emptyList()
        return hits.mapNotNull { h ->
            val o = h as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val duration = (o["duration"] as? JsonPrimitive)?.intOrNull ?: 0
            val videos = o["videos"] as? JsonObject ?: return@mapNotNull null
            val files = listOf("large", "medium", "small").mapNotNull { size ->
                val f = videos[size] as? JsonObject ?: return@mapNotNull null
                val url = (f["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                VideoFile(url, (f["width"] as? JsonPrimitive)?.intOrNull ?: 0, (f["height"] as? JsonPrimitive)?.intOrNull ?: 0)
            }
            Video(id, duration, files, (o["tags"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }

    fun parsePhotos(json: String): List<Photo> {
        val root = Http.json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val hits = root["hits"] as? JsonArray ?: return emptyList()
        return hits.mapNotNull { h ->
            val o = h as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val url = ((o["largeImageURL"] ?: o["webformatURL"]) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Photo(
                id, url,
                (o["imageWidth"] as? JsonPrimitive)?.intOrNull ?: 0,
                (o["imageHeight"] as? JsonPrimitive)?.intOrNull ?: 0,
                (o["tags"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
    }

    /**
     * File migliore per un video 9:16: prima quelli verticali; tra gli orizzontali solo quelli
     * abbastanza alti da poter ritagliare la parte centrale (almeno 1080 px).
     */
    fun chooseFile(files: List<VideoFile>): VideoFile? {
        val vertical = files.filter { it.height >= it.width && it.height >= 960 }
        if (vertical.isNotEmpty()) return vertical.minByOrNull { kotlin.math.abs(it.height - 1920) }
        return files.filter { it.height >= 1080 && it.width <= 3840 }.maxByOrNull { it.height }
    }

    /** Prima i video verticali, poi gli orizzontali ritagliabili; mai uno già usato né con persone (se vietate). */
    fun chooseVideo(videos: List<Video>, minDurationMs: Long, usedIds: Set<String>, allowPeople: Boolean = true, subject: String = ""): Pair<Video, VideoFile>? =
        chooseVideos(videos, minDurationMs, usedIds, allowPeople, 1, subject).firstOrNull()

    /** Fino a [max] video adatti: prima i verticali, poi gli orizzontali ritagliabili. */
    fun chooseVideos(videos: List<Video>, minDurationMs: Long, usedIds: Set<String>, allowPeople: Boolean, max: Int, subject: String = ""): List<Pair<Video, VideoFile>> {
        val candidates = videos
            .filter { videoId(it.id) !in usedIds && it.durationSec * 1000L >= minDurationMs }
            .filter { ClipFilter.allowed(it.description, allowPeople, subject) }
            .mapNotNull { v -> chooseFile(v.files)?.let { v to it } }
        val (vertical, horizontal) = candidates.partition { (_, f) -> f.height >= f.width }
        return (vertical + horizontal).take(max)
    }
}
