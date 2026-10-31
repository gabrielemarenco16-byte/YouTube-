package it.shortsgen.app.video

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.image.ImageTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Contenuto visivo di una scena: un'immagine oppure una clip video (tagliata a [durationMs]). */
data class SceneVisual(val file: File, val durationMs: Long, val isVideo: Boolean = false)

/** Montaggio del video finale con Media3 Transformer. */
@OptIn(UnstableApi::class)
class VideoComposer(private val context: Context) {

    companion object {
        private const val TAG = "VideoComposer"
        private const val FRAME_RATE = 30
        private const val FADE_US = 220_000L
        private const val OUTPUT_SAMPLE_RATE = 44_100
    }

    /**
     * Crea [output] (MP4 H.264 + AAC, 1080x1920).
     * Prima prova in un solo passaggio (sequenza immagini + sequenza audio); se il
     * dispositivo non lo supporta, esporta video e audio separatamente e li unisce.
     */
    suspend fun compose(
        scenes: List<SceneVisual>,
        audio: File,
        cues: List<SubtitleCue>,
        output: File,
        onProgress: (Float) -> Unit,
    ) {
        require(scenes.isNotEmpty())
        output.delete()
        try {
            export(buildComposition(scenes, audio, cues, includeAudio = true), output) { onProgress(it) }
            return
        } catch (e: ExportException) {
            Log.w(TAG, "Export in un passaggio fallito, uso il percorso alternativo", e)
        }

        val dir = output.parentFile ?: context.cacheDir
        val videoOnly = File(dir, "video_only.mp4").apply { delete() }
        val audioOnly = File(dir, "audio_only.m4a").apply { delete() }
        try {
            export(buildComposition(scenes, audio, cues, includeAudio = false), videoOnly) { onProgress(it * 0.8f) }
            export(buildAudioComposition(audio), audioOnly) { onProgress(0.8f + it * 0.15f) }
            withContext(Dispatchers.IO) { Mp4Muxing.mux(videoOnly, audioOnly, output) }
            onProgress(1f)
        } catch (e: ExportException) {
            throw GenerationException(
                "Il montaggio del video non è riuscito su questo telefono (${e.errorCodeName}). Riprova.",
                e,
            )
        } finally {
            videoOnly.delete()
            audioOnly.delete()
        }
    }

    private fun buildComposition(
        scenes: List<SceneVisual>,
        audio: File,
        cues: List<SubtitleCue>,
        includeAudio: Boolean,
    ): Composition {
        val videoItems = scenes.mapIndexed { i, scene ->
            val durationUs = scene.durationMs * 1000
            val fade = FadeEffect(
                durationUs,
                fadeInUs = if (i == 0) 0 else FADE_US,
                fadeOutUs = if (i == scenes.lastIndex) FADE_US * 2 else FADE_US,
            )
            // Ritaglio al centro in 9:16 e ridimensionamento a 1080x1920
            val crop = Presentation.createForWidthAndHeight(
                ImageTools.VIDEO_WIDTH, ImageTools.VIDEO_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
            )
            if (scene.isVideo) {
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(scene.file))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(scene.durationMs).build()
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(true) // l'unico audio è la voce narrante
                    .setEffects(Effects(emptyList(), listOf<Effect>(crop, fade)))
                    .build()
            } else {
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(scene.file))
                    .setMimeType(MimeTypes.IMAGE_JPEG)
                    .setImageDurationMs(scene.durationMs)
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(durationUs)
                    .setFrameRate(FRAME_RATE)
                    .setEffects(Effects(emptyList(), listOf<Effect>(crop, KenBurnsEffect(durationUs, zoomIn = i % 2 == 0), fade)))
                    .build()
            }
        }
        val sequences = mutableListOf(EditedMediaItemSequence.Builder(videoItems).build())
        if (includeAudio) sequences += EditedMediaItemSequence.Builder(listOf(audioItem(audio))).build()

        val subtitles = SubtitleOverlay(cues, ImageTools.VIDEO_WIDTH, ImageTools.VIDEO_HEIGHT)
        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), listOf<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(subtitles)))))
            .build()
    }

    private fun buildAudioComposition(audio: File): Composition =
        Composition.Builder(listOf(EditedMediaItemSequence.Builder(listOf(audioItem(audio))).build())).build()

    private fun audioItem(audio: File): EditedMediaItem {
        val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(OUTPUT_SAMPLE_RATE) }
        return EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audio)))
            .setEffects(Effects(listOf<AudioProcessor>(resampler), emptyList()))
            .build()
    }

    /** Avvia l'export sul thread principale e attende la fine, riportando l'avanzamento. */
    private suspend fun export(composition: Composition, output: File, onProgress: (Float) -> Unit): ExportResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                lateinit var poller: Runnable
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poller)
                            if (cont.isActive) cont.resume(exportResult)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            handler.removeCallbacks(poller)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                poller = Runnable {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    if (cont.isActive) handler.postDelayed(poller, 500)
                }
                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(poller)
                        transformer.cancel()
                    }
                }
                transformer.start(composition, output.absolutePath)
                handler.postDelayed(poller, 500)
            }
        }
}
