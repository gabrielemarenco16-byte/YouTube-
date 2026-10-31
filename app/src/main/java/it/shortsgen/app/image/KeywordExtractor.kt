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

    fun keywords(text: String, max: Int = 3): String =
        text.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length > 2 && it !in STOP }
            .distinct()
            .take(max)
            .joinToString(" ")

    /** Ricerche per una scena, dalla più precisa alla più generica, senza duplicati. */
    fun queries(searchQuery: String, imagePrompt: String, narration: String, topic: String): List<String> {
        val list = mutableListOf<String>()
        fun add(q: String) {
            val clean = q.trim().lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (clean.isNotEmpty() && clean !in list) list += clean
        }
        add(searchQuery)
        add(keywords(searchQuery, 2))
        add(keywords(imagePrompt, 3))
        add(keywords(narration, 3))
        add(keywords(topic, 2))
        add(keywords(imagePrompt, 1))
        return list
    }
}
