package it.shortsgen.app.voice

import java.io.File

data class WordTiming(val word: String, val startMs: Long, val endMs: Long)

data class VoiceClip(
    val file: File,
    val durationMs: Long,
    /** Tempi delle parole se il motore li fornisce, altrimenti null (verranno stimati). */
    val wordTimings: List<WordTiming>? = null,
)

/** Sintesi vocale intercambiabile: produce un file WAV PCM per ogni testo. */
interface VoiceProvider {
    /** Velocità del parlato (1.0 = normale). */
    var speechRate: Float

    suspend fun synthesize(text: String, output: File): VoiceClip

    fun release()
}
