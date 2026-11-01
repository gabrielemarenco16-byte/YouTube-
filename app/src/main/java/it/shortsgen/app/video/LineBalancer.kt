package it.shortsgen.app.video

/**
 * Sceglie dove andare a capo in un sottotitolo (dimensione del carattere fissa):
 * 1) ogni riga deve stare nella larghezza disponibile;
 * 2) il minor numero di righe;
 * 3) mai una parola sola sull'ultima riga (se il gruppo ha almeno 3 parole);
 * 4) preferibilmente nessuna riga che finisce su "the", "of", "and"…;
 * 5) righe il più possibile bilanciate.
 */
object LineBalancer {

    /** Restituisce le righe (ognuna è un elenco di parole). */
    fun lines(words: List<String>, maxWidth: Float, width: (String) -> Float): List<List<String>> {
        if (words.isEmpty()) return emptyList()
        if (width(words.joinToString(" ")) <= maxWidth || words.size == 1) return listOf(words)
        val n = words.size
        var best: List<List<String>>? = null
        var bestScore: List<Double>? = null
        // tutte le possibili divisioni in righe (le parole sono al massimo 4-5: poche combinazioni)
        for (mask in 0 until (1 shl (n - 1))) {
            val lines = ArrayList<List<String>>()
            var start = 0
            for (i in 0 until n - 1) {
                if (mask and (1 shl i) != 0) {
                    lines += words.subList(start, i + 1)
                    start = i + 1
                }
            }
            lines += words.subList(start, n)
            val widths = lines.map { width(it.joinToString(" ")) }
            val overflow = widths.zip(lines).sumOf { (w, l) -> if (w > maxWidth && l.size > 1) (w - maxWidth).toDouble() else 0.0 }
            val loneLast = if (n >= 3 && lines.size > 1 && lines.last().size == 1) 1.0 else 0.0
            val weak = lines.dropLast(1).count { SubtitleChunker.isWeakEnding(it.last()) }.toDouble()
            val balance = (widths.maxOrNull() ?: 0f) - (widths.minOrNull() ?: 0f)
            val score = listOf(if (overflow > 0) 1.0 else 0.0, overflow, lines.size.toDouble(), loneLast, weak, balance.toDouble())
            if (bestScore == null || compare(score, bestScore) < 0) {
                best = lines
                bestScore = score
            }
        }
        return best!!
    }

    private fun compare(a: List<Double>, b: List<Double>): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }
}
