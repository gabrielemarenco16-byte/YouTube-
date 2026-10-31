package it.shortsgen.app.pipeline

import android.content.Context
import android.util.Log
import it.shortsgen.app.core.GenerationException
import it.shortsgen.app.core.Http
import it.shortsgen.app.core.retry
import it.shortsgen.app.image.CloudflareProvider
import it.shortsgen.app.image.ImageProvider
import it.shortsgen.app.image.KeywordExtractor
import it.shortsgen.app.image.MediaCollector
import it.shortsgen.app.image.MediaKind
import it.shortsgen.app.image.MediaRequest
import it.shortsgen.app.image.PexelsProvider
import it.shortsgen.app.image.SceneMedia
import it.shortsgen.app.image.ImageTools
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
        val media = mediaStep(jobDir, script, audio.timeline)
        val video = File(jobDir, "short.mp4")
        videoStep(media, audio, video)
        val thumbnail = File(jobDir, "thumbnail.jpg")
        report(Phase.THUMBNAIL, 0f, "Disegno la copertina…")
        withContext(Dispatchers.Default) { ThumbnailGenerator.create(media.firstOrNull(), script.title, thumbnail) }
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

    // ---------- 3) Immagini e video ----------

    /** Provider scelto nelle Impostazioni, poi l'altro (se configurato) come riserva. */
    private fun imageProviders(): List<ImageProvider> {
        val pexels = PexelsProvider(settings.pexelsKey)
        val cloudflare = CloudflareProvider(settings.cloudflareAccountId, settings.cloudflareToken, settings.cloudflareModel)
        val hasPexels = settings.pexelsKey.isNotBlank()
        val hasCloudflare = settings.cloudflareAccountId.isNotBlank() && settings.cloudflareToken.isNotBlank()
        return when (settings.imageProvider) {
            ImageProviderType.PEXELS -> listOfNotNull(pexels, cloudflare.takeIf { hasCloudflare })
            ImageProviderType.CLOUDFLARE -> listOfNotNull(cloudflare, pexels.takeIf { hasPexels })
        }
    }

    @Serializable
    private data class MediaManifest(val media: List<SceneMedia>)

    private suspend fun mediaStep(jobDir: File, script: VideoScript, timeline: Timeline): List<SceneMedia> {
        val manifestFile = File(jobDir, "media.json")
        val existing = runCatching {
            Http.json.decodeFromString(MediaManifest.serializer(), manifestFile.readText()).media
        }.getOrDefault(emptyList())

        val requests = script.scenes.mapIndexed { i, scene ->
            MediaRequest(
                sceneIndex = i,
                queries = KeywordExtractor.queries(scene.searchQuery, scene.imagePrompt, scene.narration, script.topic),
                prompt = Prompts.imagePrompt(scene.imagePrompt),
                minDurationMs = timeline.scenes[i].durationMs,
            )
        }
        val collector = MediaCollector(
            providers = imageProviders(),
            placeholder = { index, dir ->
                withContext(Dispatchers.Default) {
                    val file = File(dir, "scene_${index + 1}.jpg")
                    val bmp = ImageTools.placeholder(index)
                    ImageTools.saveJpeg(bmp, file)
                    bmp.recycle()
                    SceneMedia(MediaKind.IMAGE, file.absolutePath, "segnaposto", "placeholder-$index", placeholder = true)
                }
            },
            log = { Log.w(TAG, it) },
            onStatus = { i, text -> report(Phase.IMAGES, i.toFloat() / script.scenes.size, text) },
        )
        val result = collector.collect(requests, jobDir, existing)
        manifestFile.writeText(Http.json.encodeToString(MediaManifest.serializer(), MediaManifest(result.media)))
        warnings += result.warnings

        if (result.placeholderCount == script.scenes.size) {
            // Nessuna scena riuscita: quasi sempre chiave mancante o non valida. Meglio fermarsi.
            throw GenerationException("Nessuna immagine o video trovato. ${result.warnings.firstOrNull().orEmpty()}")
        }
        val videos = result.media.count { it.kind == MediaKind.VIDEO && !it.placeholder }
        report(Phase.IMAGES, 1f, "Pronte ${result.media.size} scene ($videos clip video)")
        return result.media
    }

    // ---------- 4) Montaggio ----------

    private suspend fun videoStep(media: List<SceneMedia>, audio: AudioPlan, output: File) {
        report(Phase.VIDEO, 0f, "Monto il video…")
        val visuals = audio.timeline.scenes.map { slot ->
            val m = media[slot.index]
            SceneVisual(m.file, slot.durationMs, isVideo = m.kind == MediaKind.VIDEO)
        }
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

    private companion object {
        const val TAG = "GenerationPipeline"
    }
}
