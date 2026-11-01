package it.shortsgen.app.script

/** Confronto "sfumato" tra argomenti per evitare di ripetere la stessa curiosità. */
object TopicMatcher {
    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "in", "on", "is", "are", "why", "how", "what", "to", "for",
        "that", "this", "with", "can", "do", "does", "you", "your", "its", "it", "about", "fact", "facts",
        "curiosity", "secret", "truth", "really", "most", "world", "s", "have", "has", "had", "was",
        "were", "be", "been", "there", "they", "their", "than", "from", "by", "at", "as", "or",
    )

    fun keywords(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in STOP_WORDS }
            // stemming grezzo: le prime 5 lettere bastano a unire singolare/plurale
            .map { it.take(5) }
            .toSet()

    fun similarity(a: String, b: String): Double {
        val ka = keywords(a)
        val kb = keywords(b)
        if (ka.isEmpty() || kb.isEmpty()) return if (a.trim().equals(b.trim(), ignoreCase = true)) 1.0 else 0.0
        val inter = ka.intersect(kb).size.toDouble()
        val union = ka.union(kb).size.toDouble()
        return inter / union
    }

    fun isDuplicate(candidate: String, used: Collection<String>, threshold: Double = 0.6): Boolean =
        used.any { similarity(candidate, it) >= threshold }
}
