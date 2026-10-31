package it.shortsgen.app.video

/** Verifica che i sottotitoli contengano tutte le parole del copione, nell'ordine. */
object SubtitleCheck {
    data class Report(val expectedWords: Int, val shownWords: Int, val problems: List<String>) {
        val ok: Boolean get() = problems.isEmpty()
    }

    private fun normalize(word: String) = word.uppercase().filter { it.isLetterOrDigit() }

    fun check(narrations: List<String>, cues: List<SubtitleCue>, totalMs: Long, maxWordsPerCue: Int = 3): Report {
        val expected = narrations.flatMap { SubtitleChunker.words(it) }.map(::normalize).filter { it.isNotEmpty() }
        val shown = cues.flatMap { it.text.split(" ") }.map(::normalize).filter { it.isNotEmpty() }
        val problems = mutableListOf<String>()
        val firstDiff = expected.indices.firstOrNull { it >= shown.size || shown[it] != expected[it] }
        if (firstDiff != null || shown.size != expected.size) {
            val at = firstDiff ?: expected.size
            problems += "Parole diverse dal copione dalla posizione ${at + 1}: atteso " +
                "\"${expected.drop(at).take(4).joinToString(" ")}\", mostrato \"${shown.drop(at).take(4).joinToString(" ")}\""
        }
        cues.filter { it.text.split(" ").size > maxWordsPerCue }.forEach { problems += "Sottotitolo con troppe parole: \"${it.text}\"" }
        cues.filter { it.endMs <= it.startMs }.forEach { problems += "Sottotitolo senza durata: \"${it.text}\"" }
        cues.zipWithNext().filter { (a, b) -> b.startMs < a.endMs }.forEach { (a, b) ->
            problems += "Sottotitoli sovrapposti: \"${a.text}\" / \"${b.text}\""
        }
        cues.lastOrNull()?.let { if (it.endMs > totalMs + 1) problems += "L'ultimo sottotitolo finisce dopo la fine del video" }
        return Report(expected.size, shown.size, problems)
    }
}
