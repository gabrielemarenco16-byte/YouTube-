package it.shortsgen.app.image

/** Ricava parole chiave inglesi per la ricerca di video/foto di repertorio. */
object KeywordExtractor {
    private val STOP = setOf(
        "a", "an", "the", "of", "and", "or", "in", "on", "at", "to", "for", "with", "from", "by", "as", "is",
        "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those", "their", "they",
        "you", "your", "we", "our", "he", "she", "his", "her", "them", "what", "which", "who", "how", "why",
        "when", "where", "did", "do", "does", "can", "could", "would", "will", "just", "only", "very", "more",
        "most", "than", "then", "so", "but", "not", "no", "yes", "all", "any", "some", "every", "into", "over",
        "under", "about", "after", "before", "know", "think", "imagine", "actually", "really", "even", "still",
        "here", "there", "now", "ever", "never", "one", "two", "three", "million", "billion", "thousand",
        "hundred", "follow", "subscribe", "like", "fact", "facts", "curiosity", "amazing", "incredible",
        "vertical", "composition", "photorealistic", "cinematic", "lighting", "dramatic", "detailed", "highly",
        "sharp", "focus", "image", "photo", "shot", "close", "view", "scene", "style", "realistic", "background",
        "portrait", "9", "16", "no", "text", "letters", "watermark", "has", "have", "had", "if", "while", "same",
        "make", "makes", "made", "get", "gets", "much", "many", "may", "might", "also", "out", "up", "down",
    )

    /** Temi generici sempre disponibili negli archivi, usati solo se le ricerche specifiche falliscono. */
    val GENERIC = listOf("nature landscape", "sky clouds", "ocean waves", "city night", "forest", "abstract light")

    fun keywords(text: String, max: Int = 3): String =
        text.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length > 2 && it !in STOP }
            .distinct()
            .take(max)
            .joinToString(" ")

    /** Pulisce una ricerca: minuscole, solo lettere/numeri/spazi. */
    private fun clean(q: String) =
        q.trim().lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Ricerche per una scena, nell'ordine in cui vanno provate:
     * 1) le query visive generate dall'LLM (massimo 3);
     *    se mancano, parole chiave ricavate dalla scena;
     * 2) la query generica del tema del video (es. "chemistry", "fire", "space").
     */
    fun queries(
        llmQueries: List<String>,
        theme: String,
        searchQuery: String = "",
        imagePrompt: String = "",
        narration: String = "",
        topic: String = "",
    ): List<String> {
        val list = mutableListOf<String>()
        fun add(q: String) {
            val c = clean(q)
            if (c.isNotEmpty() && c !in list) list += c
        }
        llmQueries.take(3).forEach(::add)
        if (list.isEmpty()) {
            add(searchQuery)
            add(keywords(imagePrompt, 3))
            add(keywords(narration, 3))
        }
        val themeQuery = clean(theme).ifEmpty { keywords(topic, 1) }.ifEmpty { GENERIC.first() }
        // il tema va sempre per ultimo, anche se coincide con una delle query
        list.remove(themeQuery)
        list += themeQuery
        return list
    }
}
