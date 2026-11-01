package it.shortsgen.app.image

import android.util.Log
import it.shortsgen.app.core.ApiException
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
 * Video e foto gratuiti da Pixabay (chiave PIXABAY_API_KEY).
 * Cerca prima clip video (preferendo quelle verticali); se non ce ne sono, foto verticali.
 */
class PixabayProvider(
    private val apiKey: String,
    private val client: OkHttpClient = Http.client,
    private val baseUrl: String = "https://pixabay.com",
    /** Trasforma la foto scaricata in JPEG 1080x1920 (sostituibile nei test). */
    private val photoProcessor: suspend (downloaded: File, target: File) -> Unit = ::cropPhoto,
) : ImageProvider {
    override val name = NAME

    override suspend fun fetch(request: MediaRequest, dir: File, usedIds: Set<String>): SceneMedia =
        fetchCandidates(request.copy(candidates = 1), dir, usedIds).first()

    override suspend fun fetchCandidates(request: MediaRequest, dir: File, usedIds: Set<String>): List<SceneMedia> {
        if (apiKey.isBlank()) {
            throw MissingKeyException("PIXABAY_API_KEY", "Chiave Pixabay mancante: inserisci PIXABAY_API_KEY nelle Impostazioni.")
        }
        val n = request.sceneIndex + 1
        val videoCache = HashMap<String, List<PixabayApi.Video>>()
        val photoCache = HashMap<String, List<PixabayApi.Photo>>()
        // Ordine: clip mai usate → foto mai usate → clip già usate in altri video → foto già usate.
        for (pass in listOf(Pass.FRESH_VIDEO, Pass.FRESH_PHOTO, Pass.OLD_VIDEO, Pass.OLD_PHOTO)) {
            val excluded = if (pass.fresh) usedIds + request.previouslyUsed else usedIds
            for (query in request.queries) {
                if (pass.video) {
                    val results = videoCache.getOrPut(query) { searchVideos(query) }
                    val chosen = PixabayApi.chooseVideos(
                        results, request.minDurationMs, excluded, request.allowPeople,
                        request.candidates.coerceAtLeast(1), request.subject, request.relevance,
                    )
                    if (chosen.isEmpty()) continue
                    return chosen.mapIndexed { k, (video, file) ->
                        val target = File(dir, if (chosen.size == 1) "scene_$n.mp4" else "scene_${n}_c${k + 1}.mp4")
                        client.downloadTo(file.url, target, NAME)
                        Log.i(TAG, "Scena $n: video Pixabay ${video.id} per \"$query\" (${file.width}x${file.height}) – ${video.description}")
                        SceneMedia(
                            MediaKind.VIDEO, target.absolutePath, NAME, PixabayApi.videoId(video.id),
                            query = query, detail = "video ${file.width}x${file.height}, ${video.durationSec} s",
                            durationMs = video.durationSec * 1000L, description = video.description,
                            previouslyUsed = PixabayApi.videoId(video.id) in request.previouslyUsed,
                        )
                    }
                } else {
                    val results = photoCache.getOrPut(query) { searchPhotos(query) }
                    val photo = results.firstOrNull {
                        PixabayApi.photoId(it.id) !in excluded &&
                            ClipFilter.allowed(it.description, request.allowPeople, request.subject, request.relevance)
                    } ?: continue
                    val downloaded = File(dir, "scene_$n.download")
                    val target = File(dir, "scene_$n.jpg")
                    client.downloadTo(photo.url, downloaded, NAME, maxBytes = 30L * 1024 * 1024)
                    try {
                        photoProcessor(downloaded, target)
                    } finally {
                        downloaded.delete()
                    }
                    Log.i(TAG, "Scena $n: foto Pixabay ${photo.id} per \"$query\" – ${photo.description}")
                    return listOf(
                        SceneMedia(
                            MediaKind.IMAGE, target.absolutePath, NAME, PixabayApi.photoId(photo.id),
                            query = query, detail = "foto ${photo.width}x${photo.height}", description = photo.description,
                            previouslyUsed = PixabayApi.photoId(photo.id) in request.previouslyUsed,
                        )
                    )
                }
            }
        }
        throw GenerationException(
            "Pixabay: nessun video o foto pertinente trovato per la scena $n (ricerche: ${request.queries.joinToString(", ")})."
        )
    }

    private enum class Pass(val video: Boolean, val fresh: Boolean) {
        FRESH_VIDEO(true, true), FRESH_PHOTO(false, true), OLD_VIDEO(true, false), OLD_PHOTO(false, false)
    }

    private suspend fun searchVideos(query: String): List<PixabayApi.Video> {
        val url = "$baseUrl/api/videos/".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey.trim())
            .addQueryParameter("q", query.take(100))
            .addQueryParameter("video_type", "film")
            .addQueryParameter("safesearch", "true")
            .addQueryParameter("per_page", "30")
            .build()
        return PixabayApi.parseVideos(get(url.toString()))
    }

    private suspend fun searchPhotos(query: String): List<PixabayApi.Photo> {
        val url = "$baseUrl/api/".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey.trim())
            .addQueryParameter("q", query.take(100))
            .addQueryParameter("image_type", "photo")
            .addQueryParameter("orientation", "vertical")
            .addQueryParameter("safesearch", "true")
            .addQueryParameter("per_page", "30")
            .build()
        return PixabayApi.parsePhotos(get(url.toString()))
    }

    private suspend fun get(url: String): String = try {
        client.executeForString(Request.Builder().url(url).get().build(), NAME)
    } catch (e: ApiException) {
        // Pixabay risponde 400 "Invalid or missing API key": lo trattiamo come chiave non valida (401)
        if (e.code == 400 && e.body.contains("key", ignoreCase = true)) throw ApiException(NAME, 401, e.body) else throw e
    }

    companion object {
        const val NAME = "Pixabay"
        private const val TAG = "PixabayProvider"

        private suspend fun cropPhoto(downloaded: File, target: File) = withContext(Dispatchers.Default) {
            val bitmap = ImageTools.decode(downloaded.readBytes())
                ?: throw GenerationException("Pixabay: foto scaricata non valida")
            val frame = ImageTools.centerCrop(bitmap, ImageTools.VIDEO_WIDTH, ImageTools.VIDEO_HEIGHT)
            bitmap.recycle()
            ImageTools.saveJpeg(frame, target)
            frame.recycle()
        }
    }
}
