package it.shortsgen.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.shortsgen.app.core.Http
import it.shortsgen.app.image.SceneMedia
import it.shortsgen.app.pipeline.GenerationPipeline
import it.shortsgen.app.script.Scene
import it.shortsgen.app.script.VideoScript
import it.shortsgen.app.settings.AppSettings
import it.shortsgen.app.settings.ImageProviderType
import it.shortsgen.app.settings.TextProviderType
import it.shortsgen.app.video.SubtitleCheck
import it.shortsgen.app.video.Timeline
import it.shortsgen.app.voice.AndroidTtsVoiceProvider
import it.shortsgen.app.voice.VoiceClip
import it.shortsgen.app.voice.VoiceProvider
import it.shortsgen.app.voice.WavUtils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Test completo eseguito su emulatore da GitHub Actions: genera un video vero,
 * estrae alcuni fotogrammi e scrive un report. Le chiavi arrivano SOLO come argomenti
 * dello strumento di test (dai secrets di GitHub), mai dal codice.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    private val tag = "EndToEndTest"

    private fun arg(name: String): String {
        val v = InstrumentationRegistry.getArguments().getString(name).orEmpty().trim()
        return if (v == "none") "" else v
    }

    @Serializable
    private data class Manifest(val media: List<SceneMedia>)

    @Test
    fun generateVideoAndInspectFrames() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.getExternalFilesDir(null), "e2e").apply { deleteRecursively(); mkdirs() }
        val job = File(context.filesDir, "e2e_job").apply { deleteRecursively(); mkdirs() }
        val report = StringBuilder()
        fun line(text: String) {
            Log.i(tag, text)
            report.appendLine(text)
        }

        val gemini = arg("GEMINI_API_KEY")
        val groq = arg("GROQ_API_KEY")
        val pexels = arg("PEXELS_API_KEY")
        val cfAccount = arg("CF_ACCOUNT_ID")
        val cfToken = arg("CF_API_TOKEN")
        line("Chiavi presenti: GEMINI=${gemini.isNotEmpty()} GROQ=${groq.isNotEmpty()} PEXELS=${pexels.isNotEmpty()} " +
            "CF=${cfAccount.isNotEmpty() && cfToken.isNotEmpty()}")

        if (gemini.isEmpty() && groq.isEmpty()) {
            line("Nessuna chiave per il copione: uso un copione di prova fisso.")
            File(job, "script.json").writeText(Http.json.encodeToString(VideoScript.serializer(), SAMPLE_SCRIPT))
        }
        val settings = AppSettings(
            textProvider = if (gemini.isNotEmpty()) TextProviderType.GEMINI else TextProviderType.GROQ,
            imageProvider = if (pexels.isNotEmpty() || cfAccount.isEmpty()) ImageProviderType.PEXELS else ImageProviderType.CLOUDFLARE,
            geminiKey = gemini,
            groqKey = groq,
            pexelsKey = pexels,
            cloudflareAccountId = cfAccount,
            cloudflareToken = cfToken,
        )

        val voiceKind = StringBuilder("sintesi vocale Android")
        val pipeline = GenerationPipeline(
            context, settings,
            requireRealMedia = false,
            voiceFactory = { ctx -> FallbackVoice(ctx) { voiceKind.clear().append("voce sintetica di test (TTS non disponibile sull'emulatore)") } },
            onState = { Log.i(tag, "${it.phase.label} ${(it.progress * 100).toInt()}% ${it.detail}") },
        )
        val started = System.currentTimeMillis()
        val result = pipeline.run(job)
        line("Video creato in ${(System.currentTimeMillis() - started) / 1000} s: ${result.title}")
        line("Voce: $voiceKind")
        result.warnings.forEach { line("AVVISO: $it") }

        val script = Http.json.decodeFromString(VideoScript.serializer(), File(job, "script.json").readText())
        val timeline = Http.json.decodeFromString(Timeline.serializer(), File(job, "timeline.json").readText())
        val media = Http.json.decodeFromString(Manifest.serializer(), File(job, "media.json").readText()).media

        // --- scene e fonti ---
        media.forEachIndexed { i, m ->
            line("Scena ${i + 1}: ${m.kind} da ${m.source} (${m.sourceId})${if (m.placeholder) " SEGNAPOSTO" else ""} " +
                "durata ${timeline.scenes[i].durationMs} ms – \"${script.scenes[i].narration}\"")
        }
        val placeholders = media.count { it.placeholder }
        val duplicates = media.groupBy { it.sourceId }.filter { it.value.size > 1 }.keys

        // --- sottotitoli ---
        val subs = SubtitleCheck.check(script.scenes.map { it.narration }, timeline.cues, timeline.totalMs)
        line("Sottotitoli: ${timeline.cues.size} gruppi, parole copione ${subs.expectedWords}, mostrate ${subs.shownWords}")
        subs.problems.forEach { line("PROBLEMA SOTTOTITOLI: $it") }
        timeline.cues.forEach { line("  [${it.startMs}-${it.endMs}] ${it.text}") }

        // --- durata ---
        val video = File(result.videoPath)
        val (videoTrackMs, audioTrackMs) = trackDurations(video)
        line("Durata attesa ${timeline.totalMs} ms; traccia video $videoTrackMs ms; traccia audio $audioTrackMs ms")
        val lastScene = timeline.scenes.last()
        line("Ultima scena: ${lastScene.durationMs} ms; ultimo sottotitolo \"${timeline.cues.last().text}\" " +
            "fino a ${timeline.cues.last().endMs} ms")

        // --- fotogrammi ---
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(video.absolutePath)
        val frameTimes = timeline.scenes.map { "scena_${it.index + 1}" to (it.startMs + it.durationMs / 2) } +
            listOf(
                "ultimo_sottotitolo" to (timeline.cues.last().startMs + 150),
                "fine_video" to (timeline.totalMs - 250),
            )
        frameTimes.forEach { (name, ms) ->
            val frame = retriever.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame == null) {
                line("Fotogramma $name a $ms ms: NON ESTRATTO")
            } else {
                val small = Bitmap.createScaledBitmap(frame, frame.width / 2, frame.height / 2, true)
                File(out, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                line("Fotogramma $name a $ms ms: ${frame.width}x${frame.height}")
            }
        }
        retriever.release()
        video.copyTo(File(out, "short.mp4"), overwrite = true)
        File(result.thumbnailPath).copyTo(File(out, "thumbnail.jpg"), overwrite = true)
        File(job, "timeline.json").copyTo(File(out, "timeline.json"), overwrite = true)
        File(job, "media.json").copyTo(File(out, "media.json"), overwrite = true)

        val durationOk = videoTrackMs >= timeline.totalMs - 300 && audioTrackMs >= timeline.totalMs - lastScene.durationMs / 2
        val checks = listOf(
            "nessun segnaposto" to (placeholders == 0),
            "nessun file ripetuto" to duplicates.isEmpty(),
            "sottotitoli completi" to subs.ok,
            "durata completa" to durationOk,
        )
        checks.forEach { (name, ok) -> line("CONTROLLO $name: ${if (ok) "OK" else "FALLITO"}") }
        File(out, "report.txt").writeText(report.toString())
        assertTrue("Controlli falliti, vedi report.txt:\n$report", checks.all { it.second })
    }

    private fun trackDurations(file: File): Pair<Long, Long> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var video = 0L
        var audio = 0L
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            val d = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            if (mime.startsWith("video/")) video = d
            if (mime.startsWith("audio/")) audio = d
        }
        extractor.release()
        return video to audio
    }

    /**
     * Usa la sintesi vocale di Android; se sull'emulatore non c'è, genera una voce sintetica
     * (toni per ogni parola) con durata realistica, così il resto del video si può verificare.
     */
    private class FallbackVoice(context: Context, private val onFallback: () -> Unit) : VoiceProvider {
        private val real = AndroidTtsVoiceProvider(context)
        private var useFake = false
        override var speechRate: Float = 1f

        override suspend fun synthesize(text: String, output: File): VoiceClip {
            if (!useFake) {
                try {
                    real.speechRate = speechRate
                    return real.synthesize(text, output)
                } catch (e: Exception) {
                    Log.w("EndToEndTest", "TTS non disponibile: ${e.message}")
                    useFake = true
                    onFallback()
                }
            }
            val rate = 24_000
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val samples = ArrayList<Short>()
            fun silence(ms: Int) = repeat(rate * ms / 1000) { samples += 0 }
            silence(120)
            words.forEach { w ->
                val ms = (w.length * 55 + 90)
                repeat(rate * ms / 1000) { k -> samples += (sin(k * 2 * Math.PI * 220 / rate) * 6000).toInt().toShort() }
                silence(if (w.endsWith(".") || w.endsWith("?") || w.endsWith("!")) 250 else 60)
            }
            silence(150)
            val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { data.putShort(it) }
            output.writeBytes(WavUtils.header(rate, 1, 16, samples.size * 2) + data.array())
            return VoiceClip(output, WavUtils.durationMs(output))
        }

        override fun release() = real.release()
    }

    private companion object {
        val SAMPLE_SCRIPT = VideoScript(
            topic = "Octopus three hearts",
            title = "Octopuses Have THREE Hearts and Blue Blood",
            description = "An octopus has three hearts and blue blood. Here is why. #shorts #animals #facts",
            tags = listOf("octopus", "ocean", "animals", "facts", "shorts"),
            scenes = listOf(
                Scene("Did you know that an octopus has three hearts and bright blue blood?", "octopus underwater", "octopus underwater"),
                Scene("Two of those hearts pump blood through the gills, where it picks up oxygen.", "octopus gills", "octopus swimming"),
                Scene("The third heart pushes that blood around the rest of its soft body.", "octopus body", "octopus reef"),
                Scene("When an octopus swims, that main heart actually stops beating.", "octopus swimming ocean", "ocean swimming"),
                Scene("That is why octopuses prefer crawling along the sea floor instead.", "sea floor", "sea floor"),
                Scene("Their blood is blue because it uses copper, not iron, to carry oxygen.", "blue water", "blue ocean"),
                Scene("Follow us for more incredible curiosities about our amazing planet!", "planet earth", "earth from space"),
            ),
        )
    }
}
