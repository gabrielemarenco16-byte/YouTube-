package it.shortsgen.app.image

import android.util.Log
import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.MissingKeyException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.executeForString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Video e foto gratuiti da Pexels (chiave PEXELS_API_KEY).
 * Cerca prima clip verticali abbastanza lunghe; se non ce ne sono, foto verticali.
 */
class PexelsProvider(
    private val apiKey: String,
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://api.pexels.com",
    /** Trasforma la foto scaricata in JPEG 1080x1920 (sostituibile nei test). */
    private val photoProcessor: suspend (downloaded: File, target: File) -> Unit = ::cropPhoto,
) : ImageProvider {
    override val name = NAME

    override suspend fun fetch(request: MediaRequest, dir: File, usedIds: Set<String>): SceneMedia {
        if (apiKey.isBlank()) {
            throw MissingKeyException("PEXELS_API_KEY", "Chiave Pexels mancante: inserisci PEXELS_API_KEY nelle Impostazioni.")
        }
        // 1) clip video verticali per tutte le ricerche
        for (query in request.queries) {
            val videos = searchVideos(query)
            val chosen = PexelsApi.chooseVideo(videos, request.minDurationMs, usedIds) ?: continue
            val (video, file) = chosen
            val target = File(dir, "scene_${request.sceneIndex + 1}.mp4")
            client.downloadTo(file.link, target, NAME)
            Log.i(TAG, "Scena ${request.sceneIndex + 1}: video Pexels ${video.id} per \"$query\" (${file.width}x${file.height})")
            return SceneMedia(MediaKind.VIDEO, target.absolutePath, NAME, PexelsApi.videoId(video.id))
        }
        // 2) foto verticali
        for (query in request.queries) {
            val photo = searchPhotos(query).firstOrNull { PexelsApi.photoId(it.id) !in usedIds } ?: continue
            val downloaded = File(dir, "scene_${request.sceneIndex + 1}.download")
            val target = File(dir, "scene_${request.sceneIndex + 1}.jpg")
            client.downloadTo(photo.url, downloaded, NAME, maxBytes = 30L * 1024 * 1024)
            try {
                photoProcessor(downloaded, target)
            } finally {
                downloaded.delete()
            }
            Log.i(TAG, "Scena ${request.sceneIndex + 1}: foto Pexels ${photo.id} per \"$query\"")
            return SceneMedia(MediaKind.IMAGE, target.absolutePath, NAME, PexelsApi.photoId(photo.id))
        }
        throw GenerationException(
            "Pexels: nessun video o foto adatto trovato per la scena ${request.sceneIndex + 1} " +
                "(ricerche: ${request.queries.joinToString(", ")})."
        )
    }

    private suspend fun searchVideos(query: String): List<PexelsApi.Video> {
        val url = "$baseUrl/videos/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("orientation", "portrait")
            .addQueryParameter("per_page", "20")
            .build()
        return PexelsApi.parseVideos(get(url.toString()))
    }

    private suspend fun searchPhotos(query: String): List<PexelsApi.Photo> {
        val url = "$baseUrl/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("orientation", "portrait")
            .addQueryParameter("per_page", "20")
            .build()
        return PexelsApi.parsePhotos(get(url.toString()))
    }

    private suspend fun get(url: String): String {
        val request = Request.Builder().url(url).header("Authorization", apiKey.trim()).get().build()
        return client.executeForString(request, NAME)
    }

    companion object {
        const val NAME = "Pexels"
        private const val TAG = "PexelsProvider"

        private suspend fun cropPhoto(downloaded: File, target: File) = withContext(Dispatchers.Default) {
            val bitmap = ImageTools.decode(downloaded.readBytes())
                ?: throw GenerationException("Pexels: foto scaricata non valida")
            val frame = ImageTools.centerCrop(bitmap, ImageTools.VIDEO_WIDTH, ImageTools.VIDEO_HEIGHT)
            bitmap.recycle()
            ImageTools.saveJpeg(frame, target)
            frame.recycle()
        }
    }
}
