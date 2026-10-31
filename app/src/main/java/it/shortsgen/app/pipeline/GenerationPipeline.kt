package it.shortsgen.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import it.shortsgen.app.core.ErrorMessages
import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.retry
import it.shortsgen.app.image.CloudflareImageProvider
import it.shortsgen.app.image.FallbackImageProvider
import it.shortsgen.app.image.GeminiImageProvider
import it.shortsgen.app.image.ImageProvider
import it.shortsgen.app.image.ImageTools
import it.shortsgen.app.image.PollinationsImageProvider
import it.shortsgen.app.script.GeminiTextProvider
import it.shortsgen.app.script.GroqTextProvider
import it.shortsgen.app.script.Prompts
import it.shortsgen.app.script.ScriptGenerator
import it.shortsgen.app.script.TextProvider
import it.shortsgen.app.script.VideoScript
import it.shortsgen.app.settings.AppSettings
import it.shortsgen.app.settings.ImageProviderType
import it.shortsgen.app.settings.SettingsRepository
import it.shortsgen.app.settings.TextProviderType
import it.shortsgen.app.settings.TopicHistory
import it.shortsgen.app.thumbnail.ThumbnailGenerator
import it.shortsgen.app.video.DurationPlanner
import it.shortsgen.app.video.SceneVisual
import it.shortsgen.app.video.Timeline
import it.shortsgen.app.video.TimelineBuilder
import it.shortsgen.app.video.VideoComposer
import it.shortsgen.app.voice.AndroidTtsVoiceProvider
import it.shortsgen.app.voice.VoiceProvider
import it.shortsgen.app.voice.WavUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** Esegue tutti i passaggi per creare uno Short nella cartella [jobDir]. */
class GenerationPipeline(
    private val context: Context,
    private val settings: AppSettings,
    private val onState: (GenerationState.Running) -> Unit,
) {
    private val history = TopicHistory(context)
    private val warnings = mutableListOf<String>()
    private var currentPhase = Phase.SCRIPT

    val phase: Phase get() = currentPhase

    private fun report(phase: Phase, progress: Float, detail: String) {
        currentPhase = phase
        onState(GenerationState.Running(phase, phase.overall(progress), detail))
    }

    suspend fun run(jobDir: File): GenerationResult {
        val script = scriptStep(jobDir)
        val audio = voiceStep(jobDir, script)
        val images = imageStep(jobDir, script)
        val video = File(jobDir, "short.mp4")
        videoStep(images, audio, video)
        val thumbnail = File(jobDir, "thumbnail.jpg")
        report(Phase.THUMBNAIL, 0f, "Disegno la copertina…")
        withContext(Dispatchers.Default) { ThumbnailGenerator.create(images.firstOrNull(), script.title, thumbnail) }
        report(Phase.THUMBNAIL, 1f, "Fatto!")
        return GenerationResult(
            jobDir = jobDir.absolutePath,
            title = script.title,
            description = script.description,
            tags = script.tags,
            videoPath = video.absolutePath,
            thumbnailPath = thumbnail.absolutePath,
            durationSec = ((audio.timeline.totalMs + 500) / 1000).toInt(),
            warnings = warnings.toList(),
        )
    }

    // ---------- 1) Copione ----------

    private suspend fun scriptStep(jobDir: File): VideoScript {
        val file = File(jobDir, "script.json")
        if (file.exists()) {
            runCatching { Http.json.decodeFromString(VideoScript.serializer(), file.readText()) }
                .getOrNull()?.let {
                    report(Phase.SCRIPT, 1f, "Copione recuperato: ${it.title}")
                    return it
                }
        }
        val provider = textProvider()
        report(Phase.SCRIPT, 0.1f, "Scrivo il copione con ${provider.name}…")
        val script = ScriptGenerator(provider, history).generate { report(Phase.SCRIPT, 0.5f, it) }
        file.writeText(Http.json.encodeToString(VideoScript.serializer(), script))
        history.add(script.topic)
        report(Phase.SCRIPT, 1f, "Argomento: ${script.topic}")
        return script
    }

    private fun textProvider(): TextProvider = when (settings.textProvider) {
        TextProviderType.GEMINI -> GeminiTextProvider(settings.geminiKey, settings.geminiTextModel, onModelChanged = { m ->
            saveSettings { it.copy(geminiTextModel = m) }
        })
        TextProviderType.GROQ -> GroqTextProvider(settings.groqKey, settings.groqModel)
    }

    // ---------- 2) Voce ----------

    private class AudioPlan(val narration: File, val timeline: Timeline)

    @Serializable
    private data class VoiceMeta(val rate: Float, val speechMs: List<Long>)

    private suspend fun voiceStep(jobDir: File, script: VideoScript): AudioPlan {
        val voice: VoiceProvider = AndroidTtsVoiceProvider(context)
        val metaFile = File(jobDir, "voice.json")
        try {
            var rate = settings.speechRate
            var durations: List<Long>? = runCatching {
                Http.json.decodeFromString(VoiceMeta.serializer(), metaFile.readText())
            }.getOrNull()?.takeIf { meta ->
                meta.speechMs.size == script.scenes.size &&
                    script.scenes.indices.all { sceneAudio(jobDir, it).exists() }
            }?.also { rate = it.rate }?.speechMs

            var attempt = 0
            while (durations == null) {
                voice.speechRate = rate
                val list = ArrayList<Long>()
                script.scenes.forEachIndexed { i, scene ->
                    report(Phase.VOICE, i.toFloat() / script.scenes.size, "Registro la voce: scena ${i + 1} di ${script.scenes.size}")
                    val clip = retry(times = 2, initialDelayMs = 500) { voice.synthesize(scene.narration, sceneAudio(jobDir, i)) }
                    list += clip.durationMs
                }
                val faster = DurationPlanner.adjustedRate(list, rate)
                if (faster != null && attempt < 2) {
                    report(Phase.VOICE, 0.9f, "Il video supera i 60 secondi, accelero un po' la voce…")
                    rate = faster
                    attempt++
                } else {
                    durations = list
                    metaFile.writeText(Http.json.encodeToString(VoiceMeta.serializer(), VoiceMeta(rate, list)))
                }
            }

            val speech = checkNotNull(durations)
            val pauses = DurationPlanner.pauses(speech)
            val narration = File(jobDir, "narration.wav")
            val slots = withContext(Dispatchers.IO) {
                WavUtils.concat(script.scenes.indices.map { sceneAudio(jobDir, it) }, pauses, narration)
            }
            val timeline = TimelineBuilder.build(
                script.scenes.mapIndexed { i, scene ->
                    TimelineBuilder.SceneAudio(scene.narration, speech[i], slots[i])
                }
            )
            if (timeline.totalMs > 60_000) warnings += "Il video dura ${timeline.totalMs / 1000} secondi (oltre 60)."
            report(Phase.VOICE, 1f, "Voce pronta (${timeline.totalMs / 1000} secondi)")
            return AudioPlan(narration, timeline)
        } finally {
            voice.release()
        }
    }

    private fun sceneAudio(jobDir: File, index: Int) = File(jobDir, "voice_${index + 1}.wav")

    // ---------- 3) Immagini ----------

    private fun gemini(): ImageProvider = GeminiImageProvider(settings.geminiKey, settings.geminiImageModel, onModelChanged = { m ->
        saveSettings { it.copy(geminiImageModel = m) }
    })

    private fun cloudflare(): ImageProvider = CloudflareImageProvider(
        settings.cloudflareAccountId, settings.cloudflareToken, settings.cloudflareModel,
    )

    /**
     * Servizio scelto, seguito dagli altri configurati come riserva; Pollinations
     * (senza chiave) è sempre l'ultima riserva.
     */
    private fun imageProvider(): ImageProvider {
        val hasCloudflare = settings.cloudflareAccountId.isNotBlank() && settings.cloudflareToken.isNotBlank()
        val hasGemini = settings.geminiKey.isNotBlank()
        val chain = buildList {
            when (settings.imageProvider) {
                ImageProviderType.CLOUDFLARE -> add(cloudflare())
                ImageProviderType.GEMINI -> add(gemini())
                ImageProviderType.POLLINATIONS -> add(PollinationsImageProvider())
            }
            if (settings.imageProvider != ImageProviderType.CLOUDFLARE && hasCloudflare) add(cloudflare())
            if (settings.imageProvider != ImageProviderType.GEMINI && hasGemini) add(gemini())
            if (settings.imageProvider != ImageProviderType.POLLINATIONS) add(PollinationsImageProvider())
        }
        return chain.reduceRight { primary, rest ->
            FallbackImageProvider(primary, rest) { reason ->
                warnings += "${primary.name} non ha funzionato ($reason): uso ${rest.name}."
            }
        }
    }

    private suspend fun imageStep(jobDir: File, script: VideoScript): List<File> {
        val provider = imageProvider()
        val files = ArrayList<File>()
        var failures = 0
        var firstError: Throwable? = null
        script.scenes.forEachIndexed { i, scene ->
            val file = File(jobDir, "image_${i + 1}.jpg")
            files += file
            // il file ".placeholder" indica un segnaposto: con "Riprova" si tenta di nuovo
            val marker = File(jobDir, "image_${i + 1}.placeholder")
            if (file.exists() && file.length() > 0 && !marker.exists()) return@forEachIndexed
            report(Phase.IMAGES, i.toFloat() / script.scenes.size, "Creo l'immagine ${i + 1} di ${script.scenes.size} con ${provider.name}…")
            val bitmap: Bitmap? = try {
                retry(times = 3, initialDelayMs = 3000, shouldRetry = ::isImageErrorRetryable, onRetry = { attempt, _ ->
                    report(Phase.IMAGES, i.toFloat() / script.scenes.size, "Immagine ${i + 1}: nuovo tentativo ($attempt)…")
                }) {
                    val bytes = provider.generate(Prompts.imagePrompt(scene.imagePrompt))
                    withContext(Dispatchers.Default) { ImageTools.decode(bytes) }
                        ?: throw GenerationException("Immagine ricevuta non valida")
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Immagine ${i + 1} non generata", e)
                failures++
                if (firstError == null) firstError = e
                null
            }
            withContext(Dispatchers.Default) {
                val frame = if (bitmap != null) {
                    ImageTools.centerCrop(bitmap, ImageTools.VIDEO_WIDTH, ImageTools.VIDEO_HEIGHT).also { bitmap.recycle() }
                } else {
                    ImageTools.placeholder(i, ImageTools.placeholderLabel(scene.narration))
                }
                ImageTools.saveJpeg(frame, file)
                frame.recycle()
            }
            if (bitmap == null) marker.createNewFile() else marker.delete()
            // piccola pausa per rispettare i limiti delle API gratuite
            if (i < script.scenes.lastIndex) delay(800)
        }
        if (failures > 0) {
            val reason = firstError?.let { ErrorMessages.toItalian(it, Phase.IMAGES.label) } ?: ""
            warnings += "$failures immagini su ${script.scenes.size} sostituite da un segnaposto. $reason"
            // Se nessuna immagine è riuscita è quasi sempre un problema di chiave/limiti: meglio fermarsi.
            if (failures == script.scenes.size) {
                files.forEach { it.delete() }
                throw GenerationException("Nessuna immagine generata. $reason")
            }
        }
        report(Phase.IMAGES, 1f, "Immagini pronte")
        return files
    }

    // ---------- 4) Montaggio ----------

    private suspend fun videoStep(images: List<File>, audio: AudioPlan, output: File) {
        report(Phase.VIDEO, 0f, "Monto il video…")
        val visuals = audio.timeline.scenes.map { slot -> SceneVisual(images[slot.index], slot.durationMs) }
        VideoComposer(context).compose(visuals, audio.narration, audio.timeline.cues, output) { p ->
            report(Phase.VIDEO, p, "Monto il video… ${(p * 100).toInt()}%")
        }
        if (!output.exists() || output.length() == 0L) {
            throw GenerationException("Il montaggio non ha prodotto nessun file video. Riprova.")
        }
    }

    /** Salva subito il modello scelto automaticamente, così le prossime volte si usa quello. */
    private fun saveSettings(change: (AppSettings) -> AppSettings) {
        runCatching {
            val repo = SettingsRepository(context)
            repo.save(change(repo.load()))
        }
    }

    private fun isImageErrorRetryable(e: Throwable): Boolean =
        e !is kotlin.coroutines.cancellation.CancellationException &&
            (e is GenerationException || ErrorMessages.isRetryable(e))

    private companion object {
        const val TAG = "GenerationPipeline"
    }
}
