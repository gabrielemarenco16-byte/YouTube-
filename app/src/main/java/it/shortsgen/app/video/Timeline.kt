package it.shortsgen.app.video

import it.shortsgen.app.voice.WordTiming
import kotlinx.serialization.Serializable
import it.shortsgen.app.voice.WordTimingEstimator

@Serializable
data class SubtitleCue(val text: String, val startMs: Long, val endMs: Long)

/** Una scena sulla linea temporale finale del video. */
@Serializable
data class SceneSlot(val index: Int, val startMs: Long, val durationMs: Long) {
    val endMs: Long get() = startMs + durationMs
}

@Serializable
data class Timeline(val scenes: List<SceneSlot>, val cues: List<SubtitleCue>) {
    val totalMs: Long get() = scenes.lastOrNull()?.endMs ?: 0
}

object SubtitleChunker {

    /**
     * Dimensioni dei gruppi per [n] parole: gruppi da [size]; se ne avanzerebbe una sola,
     * gli ultimi due gruppi diventano da 2 (es. 7 parole = 3+2+2), così non resta una parola isolata.
     */
    fun groupSizes(n: Int, size: Int = 3): List<Int> {
        if (n <= 0) return emptyList()
        if (n <= size) return listOf(n)
        val full = n / size
        return when (n % size) {
            0 -> List(full) { size }
            1 -> if (size == 3) List(full - 1) { size } + listOf(2, 2) else List(full) { size } + 1
            else -> List(full) { size } + (n % size)
        }
    }

    /** true se dopo questa parola finisce una frase o c'è una virgola/pausa. */
    fun endsPhrase(word: String): Boolean {
        val w = word.trimEnd('"', '\'', ')', '”', '’')
        return w.endsWith(".") || w.endsWith("!") || w.endsWith("?") || w.endsWith(",") ||
            w.endsWith(";") || w.endsWith(":") || w.endsWith("—") || w.endsWith("…")
    }

    /**
     * Divide le parole in frasi (punto, punto esclamativo/interrogativo, virgola, punto e virgola…).
     * Una frase di una sola parola viene unita alla successiva (o alla precedente se è l'ultima),
     * così non compare mai una parola da sola.
     */
    fun <T> phrases(words: List<T>, text: (T) -> String): List<List<T>> {
        val result = ArrayList<MutableList<T>>()
        var current = ArrayList<T>()
        for (w in words) {
            current += w
            if (endsPhrase(text(w))) {
                result += current
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) result += current
        // unisce le frasi di una parola
        val merged = ArrayList<MutableList<T>>()
        var carry: MutableList<T>? = null
        for (p in result) {
            val phrase = (carry ?: mutableListOf<T>()).apply { addAll(p) }
            carry = null
            if (phrase.size == 1) carry = phrase else merged += phrase
        }
        carry?.let { lone -> if (merged.isEmpty()) merged += lone else merged.last().addAll(lone) }
        return merged
    }

    /**
     * Raggruppa TUTTE le parole, in ordine: prima per frasi (o dopo le virgole), poi ogni frase
     * in righe da massimo [maxWords] parole, mai una parola sola (es. 4 parole = 2+2).
     * Ogni sottotitolo resta visibile fino all'inizio del successivo; l'ultimo fino a [endMs].
     */
    fun chunk(words: List<WordTiming>, maxWords: Int = 3, endMs: Long? = null): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        for (phrase in phrases(words) { it.word }) {
            var index = 0
            for (size in groupSizes(phrase.size, maxWords)) {
                val group = phrase.subList(index, index + size)
                index += size
                cues += SubtitleCue(group.joinToString(" ") { it.word }, group.first().startMs, group.last().endMs)
            }
        }
        return cues.mapIndexed { i, c ->
            val next = cues.getOrNull(i + 1)
            when {
                next != null -> c.copy(endMs = maxOf(next.startMs, c.startMs + 1))
                endMs != null -> c.copy(endMs = maxOf(endMs, c.endMs))
                else -> c
            }
        }
    }

    /** Divide il testo in parole; la punteggiatura isolata (es. "—") resta attaccata alla parola prima. */
    fun words(text: String): List<String> {
        val result = ArrayList<String>()
        for (token in text.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            if (token.none { it.isLetterOrDigit() } && result.isNotEmpty()) {
                result[result.lastIndex] = result.last() + token
            } else {
                result += token
            }
        }
        return result
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
        /** durata dell'audio della scena (senza la pausa aggiunta) */
        val speechMs: Long,
        /** durata della scena nel video (audio + pausa finale) */
        val slotMs: Long,
        val wordTimings: List<WordTiming>? = null,
        /** inizio/fine della voce dentro l'audio (silenzio iniziale e finale esclusi) */
        val voiceStartMs: Long = 0,
        val voiceEndMs: Long = speechMs,
    )

    fun build(scenes: List<SceneAudio>, wordsPerCue: Int = 3): Timeline {
        val slots = ArrayList<SceneSlot>()
        val cues = ArrayList<SubtitleCue>()
        var cursor = 0L
        scenes.forEachIndexed { i, s ->
            slots += SceneSlot(i, cursor, s.slotMs)
            val words = s.wordTimings ?: run {
                // Tempi stimati: la durata della voce divisa in proporzione ai caratteri
                val start = s.voiceStartMs.coerceIn(0, s.speechMs)
                val end = s.voiceEndMs.coerceIn(start, s.speechMs).takeIf { it - start > 200 } ?: s.speechMs
                WordTimingEstimator.estimate(SubtitleChunker.words(s.narration).joinToString(" "), end - start)
                    .map { it.copy(startMs = it.startMs + start, endMs = it.endMs + start) }
            }
            SubtitleChunker.chunk(words, wordsPerCue, endMs = s.slotMs).forEach { c ->
                cues += SubtitleCue(
                    SubtitleChunker.displayText(c.text),
                    cursor + c.startMs.coerceAtMost(s.slotMs - 1),
                    cursor + c.endMs.coerceAtMost(s.slotMs),
                )
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

    /** L'ultima scena dura quanto il suo audio più mezzo secondo: la frase finale non viene tagliata. */
    const val FINAL_PAUSE_MS = 500L
    const val MAX_RATE = 1.6f

    fun totalWithBasePause(speechDurations: List<Long>): Long =
        if (speechDurations.isEmpty()) 0 else
            speechDurations.sum() + BASE_PAUSE_MS * (speechDurations.size - 1) + FINAL_PAUSE_MS

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
        return List(n) { i -> (if (i == n - 1) FINAL_PAUSE_MS else BASE_PAUSE_MS) + extra }
    }
}
