package it.shortsgen.app.image

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Misura quanto si muove una clip: confronta 4 fotogrammi rimpiccioliti presi nel tratto che verrà usato. */
object MotionScorer {
    suspend fun score(media: SceneMedia, usedMs: Long): Double = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(media.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext 0.0
            val span = minOf(duration, usedMs.coerceAtLeast(1000))
            val frames = (1..4).mapNotNull { k ->
                val t = media.startMs + span * k / 5
                retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                    val small = Bitmap.createScaledBitmap(frame, 48, 85, true)
                    val pixels = IntArray(small.width * small.height)
                    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
                    if (small !== frame) small.recycle()
                    frame.recycle()
                    pixels
                }
            }
            MotionMath.score(frames)
        } catch (e: Exception) {
            0.0
        } finally {
            runCatching { retriever.release() }
        }
    }
}
