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

    /** Aggiunge il soggetto alla ricerca se non c'è già (es. "glowing water" → "jellyfish glowing water"). */
    fun withSubject(query: String, subject: String): String {
        val q = clean(query)
        val s = clean(subject)
        if (s.isEmpty()) return q
        if (q.isEmpty()) return s
        val qWords = q.split(" ")
        val subjectWords = s.split(" ")
        // "jellyfishes" o "jellyfish" contano come presenti
        val present = subjectWords.all { sw -> qWords.any { it == sw || it.startsWith(sw) || sw.startsWith(it) && it.length > 3 } }
        if (present) return q
        return (subjectWords + qWords.filter { it !in subjectWords }).take(subjectWords.size + 3).joinToString(" ")
    }

    /**
     * Ricerche per una scena, nell'ordine in cui vanno provate. Il soggetto principale del video
     * è SEMPRE presente in ogni ricerca, così nessuna scena finisce su immagini estranee al tema:
     * 1) le query della scena (massimo 3), ciascuna con il soggetto;
     *    per la scena di apertura (hook) prima le più specifiche;
     * 2) le query del tema con il soggetto (es. "jellyfish ocean");
     * 3) il soggetto da solo.
     */
    fun sceneQueries(
        llmQueries: List<String>,
        theme: String,
        themeQueries: List<String> = emptyList(),
        searchQuery: String = "",
        imagePrompt: String = "",
        narration: String = "",
        topic: String = "",
        subject: String = "",
        hook: Boolean = false,
    ): SceneQueries {
        val subj = clean(subject).ifEmpty { keywords(topic, 1) }
        fun addTo(list: MutableList<String>, q: String) {
            val c = withSubject(q, subj)
            if (c.isNotEmpty() && c !in list) list += c
        }
        val raw = mutableListOf<String>()
        llmQueries.take(3).forEach { if (clean(it).isNotEmpty()) raw += it }
        if (raw.isEmpty()) {
            listOf(searchQuery, keywords(imagePrompt, 3), keywords(narration, 3)).filter { it.isNotBlank() }.forEach { raw += it }
        }
        val scene = mutableListOf<String>()
        raw.forEach { addTo(scene, it) }
        if (hook) {
            // la ricerca più specifica (più parole) per prima: l'apertura deve essere d'impatto
            val sorted = scene.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { it.value.split(" ").size }.thenBy { it.index })
            scene.clear()
            scene += sorted.map { it.value }
        }
        val generic = mutableListOf<String>()
        themeQueries.take(5).forEach { addTo(generic, it) }
        clean(theme).takeIf { it.isNotEmpty() }?.let { addTo(generic, it) }
        // mai ricerche generiche estranee al soggetto: l'ultima ricerca è il soggetto da solo
        if (subj.isNotEmpty()) addTo(generic, subj)
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
        subject: String = "",
    ): List<String> = sceneQueries(llmQueries, theme, themeQueries, searchQuery, imagePrompt, narration, topic, subject).all
}
