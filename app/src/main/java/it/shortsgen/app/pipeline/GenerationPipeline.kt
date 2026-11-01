package it.shortsgen.app.pipeline

import android.content.Context
import android.media.MediaMetadataRetriever
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
import it.shortsgen.app.image.MotionScorer
import it.shortsgen.app.image.PeopleFilter
import it.shortsgen.app.image.PexelsProvider
import it.shortsgen.app.image.PixabayProvider
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
    /** Voce da usare (intercambiabile: nei test si può sostituire). */
    private val voiceFactory: (Context) -> VoiceProvider = ::AndroidTtsVoiceProvider,
    /** Se false, il video viene creato anche quando tutte le scene sono segnaposto (solo per i test). */
    private val requireRealMedia: Boolean = true,
    private val onState: (GenerationState.Running) -> Unit,
) {
    private val history = TopicHistory(context)
    private val warnings = mutableListOf<String>()
    private var sceneLog: List<String> = emptyList()
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
            sceneLog = sceneLog,
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
        val voice: VoiceProvider = voiceFactory(context)
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
                    val bounds = withContext(Dispatchers.IO) {
                        runCatching { WavUtils.voiceBounds(sceneAudio(jobDir, i).readBytes()) }.getOrNull()
                    }
                    TimelineBuilder.SceneAudio(
                        narration = scene.narration,
                        speechMs = speech[i],
                        slotMs = slots[i],
                        voiceStartMs = bounds?.first ?: 0,
                        voiceEndMs = bounds?.second ?: speech[i],
                    )
                }
            )
            if (timeline.totalMs > 60_000) warnings += "Il video dura ${timeline.totalMs / 1000} secondi (oltre 60)."
            // Salvata per controllo (sottotitoli e scene con i loro tempi)
            File(jobDir, "timeline.json").writeText(Http.json.encodeToString(Timeline.serializer(), timeline))
            report(Phase.VOICE, 1f, "Voce pronta (${timeline.totalMs / 1000} secondi)")
            return AudioPlan(narration, timeline)
        } finally {
            voice.release()
        }
    }

    private fun sceneAudio(jobDir: File, index: Int) = File(jobDir, "voice_${index + 1}.wav")

    // ---------- 3) Immagini e video ----------

    /** Servizi immagini in ordine: quello scelto nelle Impostazioni, poi gli altri configurati come riserva. */
    private fun imageProviders(): List<ImageProvider> = settings.imageSources().map { type ->
        when (type) {
            ImageProviderType.PEXELS -> PexelsProvider(settings.effectivePexelsKey)
            ImageProviderType.PIXABAY -> PixabayProvider(settings.effectivePixabayKey)
            ImageProviderType.CLOUDFLARE ->
                CloudflareProvider(settings.cloudflareAccountId, settings.cloudflareToken, settings.cloudflareModel)
        }
    }

    @Serializable
    private data class MediaManifest(
        val media: List<SceneMedia>,
        /** File scartati con "Cambia clip": non vanno più riproposti in questo video. */
        val rejected: List<String> = emptyList(),
    )

    private fun readManifest(jobDir: File): MediaManifest? = runCatching {
        Http.json.decodeFromString(MediaManifest.serializer(), File(jobDir, "media.json").readText())
    }.getOrNull()

    private fun writeManifest(jobDir: File, manifest: MediaManifest) =
        File(jobDir, "media.json").writeText(Http.json.encodeToString(MediaManifest.serializer(), manifest))

    private fun buildRequests(script: VideoScript, timeline: Timeline): List<MediaRequest> {
        // Persone ammesse solo se il copione ne parla
        val allowPeople = PeopleFilter.scriptMentionsPeople(
            (listOf(script.topic, script.title) + script.scenes.map { it.narration }).joinToString(" ")
        )
        return script.scenes.mapIndexed { i, scene ->
            val hook = i == 0
            val q = KeywordExtractor.sceneQueries(
                llmQueries = scene.searchQueries,
                theme = script.theme,
                themeQueries = script.themeQueries,
                searchQuery = scene.searchQuery,
                imagePrompt = scene.imagePrompt,
                narration = scene.narration,
                topic = script.topic,
                subject = script.subject,
                hook = hook,
                subjectQueries = script.subjectQueries,
            )
            MediaRequest(
                sceneIndex = i,
                queries = q.all,
                prompt = Prompts.imagePrompt(scene.imagePrompt) + if (allowPeople) "" else " No people.",
                minDurationMs = timeline.scenes[i].durationMs,
                sceneQueryCount = q.sceneCount,
                keyword = script.subject.ifBlank { q.all.firstOrNull() ?: KeywordExtractor.keywords(scene.narration, 2) },
                allowPeople = allowPeople,
                subject = script.subject,
                hook = hook && script.subject.isNotBlank(),
                // apertura: si scaricano 3 clip e si tiene la più movimentata
                candidates = if (hook) 3 else 1,
            )
        }
    }

    /** Ultima scelta: scheda grafica con la parola chiave della scena (sfondo scuro uniforme). */
    private suspend fun keywordCard(request: MediaRequest, dir: File): SceneMedia = withContext(Dispatchers.Default) {
        val file = File(dir, "scene_${request.sceneIndex + 1}_card.jpg")
        val bmp = ImageTools.keywordCard(request.keyword, request.sceneIndex)
        ImageTools.saveJpeg(bmp, file)
        bmp.recycle()
        SceneMedia(MediaKind.IMAGE, file.absolutePath, "scheda grafica", "card-${request.sceneIndex}", placeholder = true)
    }

    private fun collector(sceneCount: Int) = MediaCollector(
        providers = imageProviders(),
        placeholder = { request, dir -> keywordCard(request, dir) },
        log = { Log.w(TAG, it) },
        info = { Log.i(MEDIA_TAG, it) },
        onStatus = { i, text -> report(Phase.IMAGES, i.toFloat() / sceneCount, text) },
        motionScore = { m -> MotionScorer.score(m, usedMs = 6_000) },
    )

    private suspend fun mediaStep(jobDir: File, script: VideoScript, timeline: Timeline): List<SceneMedia> {
        val existing = readManifest(jobDir)?.media.orEmpty()
        val requests = buildRequests(script, timeline)
        val result = collector(script.scenes.size).collect(requests, jobDir, existing)
        writeManifest(jobDir, MediaManifest(result.media))
        warnings += result.warnings
        sceneLog = result.sceneLog
        File(jobDir, "scene_log.txt").writeText(result.sceneLog.joinToString("\n"))

        if (requireRealMedia && result.placeholderCount == script.scenes.size) {
            // Nessuna scena riuscita: quasi sempre chiave mancante o non valida. Meglio fermarsi.
            throw GenerationException("Nessuna clip o foto trovata. ${result.warnings.firstOrNull().orEmpty()}")
        }
        val videos = result.media.count { it.kind == MediaKind.VIDEO && !it.placeholder }
        report(Phase.IMAGES, 1f, "Pronte ${result.media.size} scene ($videos clip video)")
        return result.media
    }

    /**
     * "Cambia clip" per una scena: cerca un altro file con la stessa query (poi le altre della scena
     * e del tema), lo sostituisce e rifà SOLO il montaggio e la copertina. Copione e voce restano.
     */
    suspend fun replaceClip(previous: GenerationResult, sceneIndex: Int): GenerationResult {
        val jobDir = File(previous.jobDir)
        val script = Http.json.decodeFromString(VideoScript.serializer(), File(jobDir, "script.json").readText())
        val timeline = Http.json.decodeFromString(Timeline.serializer(), File(jobDir, "timeline.json").readText())
        val manifest = readManifest(jobDir) ?: throw GenerationException("Dati delle scene non trovati: genera un nuovo video.")
        val narration = File(jobDir, "narration.wav")
        if (!narration.exists()) throw GenerationException("Audio del video non trovato: genera un nuovo video.")
        val current = manifest.media.getOrNull(sceneIndex) ?: throw GenerationException("Scena ${sceneIndex + 1} non trovata.")

        report(Phase.IMAGES, 0.2f, "Scena ${sceneIndex + 1}: cerco un'altra clip…")
        val base = buildRequests(script, timeline)[sceneIndex]
        // prima la stessa query che aveva trovato il file attuale
        val sameQueryFirst = (listOfNotNull(current.query.takeIf { it.isNotBlank() && !current.placeholder }) + base.queries).distinct()
        val request = base.copy(
            queries = sameQueryFirst,
            sceneQueryCount = if (sameQueryFirst.size > base.queries.size) base.sceneQueryCount + 1 else base.sceneQueryCount,
        )
        val excluded = (manifest.media.map { it.sourceId } + manifest.rejected).toSet()
        val replacementDir = File(jobDir, "replace_${System.currentTimeMillis()}").apply { mkdirs() }
        val found = collector(script.scenes.size).replace(
            request, replacementDir, excluded, preferredSource = current.source.takeIf { !current.placeholder },
        ) ?: run {
            replacementDir.deleteRecursively()
            throw GenerationException(
                "Nessun'altra clip trovata per la scena ${sceneIndex + 1} (ricerche: ${request.queries.joinToString(", ")}). " +
                    "Il video non è stato modificato."
            )
        }
        val (newMedia, logLine) = found
        val media = manifest.media.toMutableList().also { it[sceneIndex] = newMedia }
        Log.i(MEDIA_TAG, "Cambio clip scena ${sceneIndex + 1}: ${current.sourceId} → ${newMedia.sourceId}")

        // montaggio su un file nuovo: se fallisce, il video precedente resta intatto
        val tmp = File(jobDir, "short_new.mp4")
        videoStep(media, AudioPlan(narration, timeline), tmp)
        val video = File(previous.videoPath)
        if (!tmp.renameTo(video)) {
            tmp.copyTo(video, overwrite = true)
            tmp.delete()
        }
        writeManifest(jobDir, MediaManifest(media, manifest.rejected + current.sourceId))
        if (sceneIndex == 0) {
            report(Phase.THUMBNAIL, 0f, "Aggiorno la copertina…")
            withContext(Dispatchers.Default) { ThumbnailGenerator.create(newMedia, script.title, File(previous.thumbnailPath)) }
        }
        val newLog = previous.sceneLog.toMutableList()
        if (sceneIndex < newLog.size) newLog[sceneIndex] = logLine else newLog += logLine
        File(jobDir, "scene_log.txt").writeText(newLog.joinToString("\n"))
        report(Phase.THUMBNAIL, 1f, "Clip della scena ${sceneIndex + 1} sostituita")
        return previous.copy(
            sceneLog = newLog,
            warnings = previous.warnings.filterNot { it.contains("scena ${sceneIndex + 1} ") } + warnings,
            createdAt = System.currentTimeMillis(),
        )
    }

    // ---------- 4) Montaggio ----------

    private suspend fun videoStep(media: List<SceneMedia>, audio: AudioPlan, output: File) {
        report(Phase.VIDEO, 0f, "Monto il video…")
        val visuals = audio.timeline.scenes.map { slot ->
            val m = media[slot.index]
            SceneVisual(m.file, slot.durationMs, isVideo = m.kind == MediaKind.VIDEO, startMs = m.startMs)
        }
        VideoComposer(context).compose(visuals, audio.narration, audio.timeline.cues, output) { p ->
            report(Phase.VIDEO, p, "Monto il video… ${(p * 100).toInt()}%")
        }
        if (!output.exists() || output.length() == 0L) {
            throw GenerationException("Il montaggio non ha prodotto nessun file video. Riprova.")
        }
        // Controllo finale: il video deve durare quanto la voce (più la pausa finale), senza tagli.
        val actualMs = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(output.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                r.release()
            }
        }.getOrNull()
        val expectedMs = audio.timeline.totalMs
        if (actualMs != null && actualMs < expectedMs - 300) {
            val msg = "Il video esportato dura ${actualMs / 1000.0} s invece di ${expectedMs / 1000.0} s: il finale potrebbe essere tagliato."
            Log.w(TAG, msg)
            warnings += msg
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
        /** Tag del logcat con query e file scelti per ogni scena: adb logcat -s ShortsMedia */
        const val MEDIA_TAG = "ShortsMedia"
    }
}
