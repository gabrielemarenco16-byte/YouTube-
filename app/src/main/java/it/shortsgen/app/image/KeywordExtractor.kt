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

    /** Ricerche per una scena: specifiche + del tema, con il numero di quelle specifiche. */
    data class SceneQueries(val all: List<String>, val sceneCount: Int)

    /**
     * Ricerche per una scena, nell'ordine in cui vanno provate:
     * 1) le query visive della scena generate dall'LLM (massimo 3; se mancano, parole chiave ricavate dalla scena);
     * 2) le query generiche del tema (es. "tree", "forest", "seed pod", "plant") e il tema stesso.
     */
    fun sceneQueries(
        llmQueries: List<String>,
        theme: String,
        themeQueries: List<String> = emptyList(),
        searchQuery: String = "",
        imagePrompt: String = "",
        narration: String = "",
        topic: String = "",
    ): SceneQueries {
        val scene = mutableListOf<String>()
        fun addTo(list: MutableList<String>, q: String) {
            val c = clean(q)
            if (c.isNotEmpty() && c !in list) list += c
        }
        llmQueries.take(3).forEach { addTo(scene, it) }
        if (scene.isEmpty()) {
            addTo(scene, searchQuery)
            addTo(scene, keywords(imagePrompt, 3))
            addTo(scene, keywords(narration, 3))
        }
        val generic = mutableListOf<String>()
        themeQueries.take(5).forEach { addTo(generic, it) }
        addTo(generic, clean(theme).ifEmpty { keywords(topic, 1) }.ifEmpty { GENERIC.first() })
        val themePart = generic.filter { it !in scene }
        return SceneQueries(scene + themePart, scene.size)
    }

    /** Solo l'elenco delle ricerche (vedi [sceneQueries]). */
    fun queries(
        llmQueries: List<String>,
        theme: String,
        searchQuery: String = "",
        imagePrompt: String = "",
        narration: String = "",
        topic: String = "",
        themeQueries: List<String> = emptyList(),
    ): List<String> = sceneQueries(llmQueries, theme, themeQueries, searchQuery, imagePrompt, narration, topic).all
}
