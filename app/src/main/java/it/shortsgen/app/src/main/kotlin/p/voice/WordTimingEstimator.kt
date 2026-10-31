package it.shortsgen.app.voice

/**
 * Stima l'inizio/fine di ogni parola quando il motore vocale non fornisce i tempi:
 * la durata dell'audio viene divisa in proporzione ai caratteri, con pause extra
 * dopo virgole e punti.
 */
object WordTimingEstimator {
    private const val COMMA_PAUSE = 3.0
    private const val SENTENCE_PAUSE = 6.0

    fun estimate(text: String, durationMs: Long, leadInMs: Long = 0, tailMs: Long = 0): List<WordTiming> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || durationMs <= 0) return emptyList()
        val speechMs = (durationMs - leadInMs - tailMs).coerceAtLeast(durationMs / 2)

        val weights = words.map { w ->
            val letters = w.count { it.isLetterOrDigit() }.coerceAtLeast(1)
            letters + 1.5
        }
        val pauses = words.map { w ->
            when {
                w.endsWith(".") || w.endsWith("!") || w.endsWith("?") -> SENTENCE_PAUSE
                w.endsWith(",") || w.endsWith(";") || w.endsWith(":") || w.endsWith("—") -> COMMA_PAUSE
                else -> 0.0
            }
        }
        // l'ultima pausa non conta: dopo l'ultima parola c'è già il silenzio finale
        val total = weights.sum() + pauses.dropLast(1).sum()
        val msPerUnit = speechMs / total

        val result = ArrayList<WordTiming>(words.size)
        var cursor = leadInMs.toDouble()
        words.forEachIndexed { i, w ->
            val start = cursor
            val end = start + weights[i] * msPerUnit
            result += WordTiming(w, start.toLong(), end.toLong())
            cursor = end + if (i < words.lastIndex) pauses[i] * msPerUnit else 0.0
        }
        return result
    }
}
