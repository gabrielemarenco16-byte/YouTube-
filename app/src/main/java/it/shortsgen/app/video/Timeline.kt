package it.shortsgen.app.video

import it.shortsgen.app.voice.WordTiming
import it.shortsgen.app.voice.WordTimingEstimator

data class SubtitleCue(val text: String, val startMs: Long, val endMs: Long)

/** Una scena sulla linea temporale finale del video. */
data class SceneSlot(val index: Int, val startMs: Long, val durationMs: Long) {
    val endMs: Long get() = startMs + durationMs
}

data class Timeline(val scenes: List<SceneSlot>, val cues: List<SubtitleCue>) {
    val totalMs: Long get() = scenes.lastOrNull()?.endMs ?: 0
}

object SubtitleChunker {

    /**
     * Raggruppa le parole a [maxWords] per volta. Un gruppo si chiude prima
     * se una parola termina una frase, così i sottotitoli seguono il senso.
     */
    fun chunk(words: List<WordTiming>, maxWords: Int = 3): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        var group = ArrayList<WordTiming>()
        fun flush() {
            if (group.isEmpty()) return
            cues += SubtitleCue(
                text = group.joinToString(" ") { it.word },
                startMs = group.first().startMs,
                endMs = group.last().endMs,
            )
            group = ArrayList()
        }
        for (w in words) {
            group += w
            val endsSentence = w.word.endsWith(".") || w.word.endsWith("!") || w.word.endsWith("?")
            if (group.size >= maxWords || endsSentence) flush()
        }
        flush()
        // Ogni sottotitolo resta visibile fino all'inizio del successivo (niente lampeggi).
        return cues.mapIndexed { i, c ->
            val next = cues.getOrNull(i + 1)
            if (next != null && next.startMs - c.endMs in 1..600) c.copy(endMs = next.startMs) else c
        }
    }

    /** Indice del sottotitolo attivo al tempo [ms] (ricerca binaria), -1 se nessuno. */
    fun cueIndexAt(cues: List<SubtitleCue>, ms: Long): Int {
        var lo = 0
        var hi = cues.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            when {
                ms < c.startMs -> hi = mid - 1
                ms >= c.endMs -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    /** Testo pulito per lo schermo: maiuscolo, senza punteggiatura finale superflua. */
    fun displayText(text: String): String =
        text.uppercase().trim().trimEnd(',', ';', ':', '.').trim()
}

object TimelineBuilder {

    data class SceneAudio(
        val narration: String,
        /** durata del parlato della scena */
        val speechMs: Long,
        /** durata della scena nel video (parlato + pausa finale) */
        val slotMs: Long,
        val wordTimings: List<WordTiming>? = null,
    )

    fun build(scenes: List<SceneAudio>, wordsPerCue: Int = 3): Timeline {
        val slots = ArrayList<SceneSlot>()
        val cues = ArrayList<SubtitleCue>()
        var cursor = 0L
        scenes.forEachIndexed { i, s ->
            slots += SceneSlot(i, cursor, s.slotMs)
            val words = s.wordTimings ?: WordTimingEstimator.estimate(s.narration, s.speechMs)
            SubtitleChunker.chunk(words, wordsPerCue).forEach { c ->
                val end = minOf(c.endMs, s.slotMs)
                cues += SubtitleCue(SubtitleChunker.displayText(c.text), cursor + c.startMs, cursor + end)
            }
            cursor += s.slotMs
        }
        return Timeline(slots, cues)
    }
}

/** Regole per restare nella durata di uno Short (30-60 s). */
object DurationPlanner {
    const val MIN_TOTAL_MS = 30_000L
    const val MAX_TOTAL_MS = 59_000L
    const val TARGET_MAX_MS = 56_000L
    const val BASE_PAUSE_MS = 350L
    const val MAX_RATE = 1.6f

    fun totalWithBasePause(speechDurations: List<Long>): Long =
        speechDurations.sum() + BASE_PAUSE_MS * speechDurations.size

    /** Se il video è troppo lungo, nuova velocità del parlato; null se va bene così. */
    fun adjustedRate(speechDurations: List<Long>, currentRate: Float): Float? {
        val total = totalWithBasePause(speechDurations)
        if (total <= MAX_TOTAL_MS || currentRate >= MAX_RATE) return null
        val speech = speechDurations.sum().toDouble()
        val wantedSpeech = TARGET_MAX_MS - BASE_PAUSE_MS * speechDurations.size
        val factor = speech / wantedSpeech.coerceAtLeast(1)
        return (currentRate * factor * 1.03).toFloat().coerceAtMost(MAX_RATE)
    }

    /** Pausa da aggiungere dopo ogni scena: allunga il video fino a 30 s se serve. */
    fun pauses(speechDurations: List<Long>): List<Long> {
        val n = speechDurations.size
        if (n == 0) return emptyList()
        val total = totalWithBasePause(speechDurations)
        val extra = if (total < MIN_TOTAL_MS) (MIN_TOTAL_MS + 500 - total) / n else 0L
        return List(n) { BASE_PAUSE_MS + extra }
    }
}
