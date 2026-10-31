package it.shortsgen.app.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import it.shortsgen.app.core.GenerationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Voce basata sul TextToSpeech di sistema (motore Google o altro installato). */
class AndroidTtsVoiceProvider(private val context: Context) : VoiceProvider {

    override var speechRate: Float = 1.0f

    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private suspend fun engine(): TextToSpeech {
        tts?.let { return it }
        val created = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<TextToSpeech> { cont ->
                var instance: TextToSpeech? = null
                instance = TextToSpeech(context.applicationContext) { status ->
                    val engine = instance
                    if (status == TextToSpeech.SUCCESS && engine != null) {
                        cont.resume(engine)
                    } else {
                        engine?.shutdown()
                        cont.resumeWithException(
                            GenerationException(
                                "Sintesi vocale non disponibile. Installa o attiva \"Servizi di sintesi vocale di Google\" " +
                                    "nelle impostazioni di Android e riprova."
                            )
                        )
                    }
                }
                cont.invokeOnCancellation { if (!cont.isCompleted) instance?.shutdown() }
            }
        }
        configure(created)
        tts = created
        return created
    }

    private fun configure(engine: TextToSpeech) {
        val result = engine.setLanguage(Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.shutdown()
            throw GenerationException(
                "La voce inglese non è installata. Vai in Impostazioni Android > Sintesi vocale, " +
                    "scarica la lingua \"English (United States)\" e riprova."
            )
        }
        pickBestVoice(engine)?.let { engine.voice = it }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                fail(utteranceId, -1)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                fail(utteranceId, errorCode)
            }
        })
    }

    private fun fail(utteranceId: String?, code: Int) {
        pending.remove(utteranceId)?.completeExceptionally(
            GenerationException("La sintesi vocale ha restituito un errore (codice $code). Riprova.")
        )
    }

    /** Preferisce una voce inglese installata localmente e di qualità alta. */
    private fun pickBestVoice(engine: TextToSpeech): Voice? = runCatching {
        engine.voices.orEmpty()
            .filter { it.locale.language == "en" }
            .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .filter { !it.isNetworkConnectionRequired }
            .sortedWith(
                compareByDescending<Voice> { it.locale.country == "US" }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
            )
            .firstOrNull()
    }.getOrNull()

    override suspend fun synthesize(text: String, output: File): VoiceClip {
        val engine = engine()
        engine.setSpeechRate(speechRate)
        output.parentFile?.mkdirs()
        output.delete()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
        val queued = engine.synthesizeToFile(text, params, output, id)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw GenerationException("Impossibile avviare la sintesi vocale. Riprova.")
        }
        try {
            withTimeout(90_000) { done.await() }
        } finally {
            pending.remove(id)
        }
        if (!output.exists() || output.length() < 100) {
            throw GenerationException("La sintesi vocale non ha prodotto audio. Controlla che la voce inglese sia installata.")
        }
        val duration = withContext(Dispatchers.IO) { WavUtils.durationMs(output) }
        return VoiceClip(output, duration, wordTimings = null)
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}
