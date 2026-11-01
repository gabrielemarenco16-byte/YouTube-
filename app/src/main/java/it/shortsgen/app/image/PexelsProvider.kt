package it.shortsgen.app.image

import android.util.Log
import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.MissingKeyException
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
 * Scarta i file con persone quando il copione non ne parla.
 */
class PexelsProvider(
    private val apiKey: String,
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://api.pexels.com",
    /** Trasforma la foto scaricata in JPEG 1080x1920 (sostituibile nei test). */
    private val photoProcessor: suspend (downloaded: File, target: File) -> Unit = ::cropPhoto,
) : ImageProvider {
    override val name = NAME

    override suspend fun fetch(request: MediaRequest, dir: File, usedIds: Set<String>): SceneMedia =
        fetchCandidates(request.copy(candidates = 1), dir, usedIds).first()

    override suspend fun fetchCandidates(request: MediaRequest, dir: File, usedIds: Set<String>): List<SceneMedia> {
        if (apiKey.isBlank()) {
            throw MissingKeyException("PEXELS_API_KEY", "Chiave Pexels mancante: inserisci PEXELS_API_KEY nelle Impostazioni.")
        }
        val n = request.sceneIndex + 1
        // 1) clip video per tutte le ricerche
        for (query in request.queries) {
            val chosen = PexelsApi.chooseVideos(searchVideos(query), request.minDurationMs, usedIds, request.allowPeople, request.candidates.coerceAtLeast(1))
            if (chosen.isEmpty()) continue
            return chosen.mapIndexed { k, (video, file) ->
                val target = File(dir, if (chosen.size == 1) "scene_$n.mp4" else "scene_${n}_c${k + 1}.mp4")
                client.downloadTo(file.link, target, NAME)
                Log.i(TAG, "Scena $n: video Pexels ${video.id} per \"$query\" (${file.width}x${file.height}) – ${video.description}")
                SceneMedia(
                    MediaKind.VIDEO, target.absolutePath, NAME, PexelsApi.videoId(video.id),
                    query = query, detail = "video ${file.width}x${file.height}, ${video.durationSec} s",
                    durationMs = video.durationSec * 1000L, description = video.description,
                )
            }
        }
        // 2) foto verticali
        for (query in request.queries) {
            val photo = searchPhotos(query).firstOrNull {
                PexelsApi.photoId(it.id) !in usedIds && PeopleFilter.allowed(it.description, request.allowPeople)
            } ?: continue
            val downloaded = File(dir, "scene_$n.download")
            val target = File(dir, "scene_$n.jpg")
            client.downloadTo(photo.url, downloaded, NAME, maxBytes = 30L * 1024 * 1024)
            try {
                photoProcessor(downloaded, target)
            } finally {
                downloaded.delete()
            }
            Log.i(TAG, "Scena $n: foto Pexels ${photo.id} per \"$query\" – ${photo.description}")
            return listOf(
                SceneMedia(
                    MediaKind.IMAGE, target.absolutePath, NAME, PexelsApi.photoId(photo.id),
                    query = query, detail = "foto", description = photo.description,
                )
            )
        }
        throw GenerationException(
            "Pexels: nessun video o foto adatto trovato per la scena $n (ricerche: ${request.queries.joinToString(", ")})."
        )
    }

    private suspend fun searchVideos(query: String): List<PexelsApi.Video> {
        val url = "$baseUrl/videos/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("orientation", "portrait")
            .addQueryParameter("per_page", "30")
            .build()
        return PexelsApi.parseVideos(get(url.toString()))
    }

    private suspend fun searchPhotos(query: String): List<PexelsApi.Photo> {
        val url = "$baseUrl/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("orientation", "portrait")
            .addQueryParameter("per_page", "30")
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
