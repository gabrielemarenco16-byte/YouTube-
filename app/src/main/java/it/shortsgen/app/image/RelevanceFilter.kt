package it.shortsgen.app.image

/**
 * Controllo di pertinenza di una clip/foto in base ai tag (Pixabay) o al titolo (Pexels):
 * - deve avere almeno un termine in comune con il soggetto, i suoi sinonimi o il suo habitat;
 * - se il soggetto è marino (o d'acqua dolce) si scartano i file con tag terrestri.
 */
data class Relevance(
    /** Termini accettati (soggetto, sinonimi, habitat). Vuoto = nessun controllo. */
    val terms: Set<String> = emptySet(),
    /** Termini che fanno scartare il file (es. tag terrestri per un soggetto marino). */
    val excluded: Set<String> = emptySet(),
) {
    /** Termini in comune tra la descrizione e quelli accettati. */
    fun matches(description: String): Set<String> = RelevanceFilter.words(description).filter { it in terms }.toSet()

    fun allowed(description: String): Boolean {
        val words = RelevanceFilter.words(description)
        if (words.any { it in excluded }) return false
        return terms.isEmpty() || words.any { it in terms }
    }
}

object RelevanceFilter {
    val MARINE_WORDS = setOf("sea", "ocean", "underwater", "marine", "reef", "coral", "water", "deep", "aquatic", "aquarium", "diving", "seabed", "seafloor")
    val FRESHWATER_WORDS = setOf("river", "lake", "pond", "stream", "water", "underwater", "aquatic", "aquarium", "freshwater")
    val AIR_WORDS = setOf("sky", "flying", "flight", "air", "clouds", "wings")
    val SPACE_WORDS = setOf("space", "galaxy", "universe", "stars", "planet", "cosmos", "nebula", "astronomy")

    /** Tag "terrestri": esclusi quando il soggetto vive in acqua. */
    val LAND_WORDS = setOf(
        "land", "garden", "moss", "grass", "lawn", "meadow", "field", "forest", "woods", "jungle", "desert",
        "mountain", "mountains", "soil", "dirt", "mud", "park", "farm", "tree", "trees", "leaf", "leaves",
        "flower", "flowers", "road", "street", "city", "house", "room", "snow", "rock", "hill", "valley",
    )

    /** Parole di una descrizione, minuscole, con anche il singolare dei plurali regolari. */
    fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z]+")).filter { it.length > 1 }
            .flatMap { w ->
                when {
                    w.length > 4 && w.endsWith("es") && !w.endsWith("ses") -> listOf(w, w.dropLast(1), w.dropLast(2))
                    w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> listOf(w, w.dropLast(1))
                    else -> listOf(w)
                }
            }
            .toSet()

    /** Costruisce il controllo dal copione: soggetto, riserve, sinonimi e habitat. */
    fun build(subject: String, subjectQueries: List<String>, subjectTerms: List<String>, habitat: String): Relevance {
        val terms = (listOf(subject) + subjectQueries + subjectTerms).flatMap { words(it) }.toMutableSet()
        val excluded = mutableSetOf<String>()
        when (habitat.lowercase()) {
            "marine", "sea", "ocean" -> {
                terms += MARINE_WORDS
                excluded += LAND_WORDS
            }
            "freshwater", "river", "lake" -> {
                terms += FRESHWATER_WORDS
                excluded += LAND_WORDS - setOf("rock", "tree", "trees", "leaf", "leaves", "forest")
            }
            "air", "sky" -> terms += AIR_WORDS
            "space" -> terms += SPACE_WORDS
        }
        // un termine accettato non può essere anche escluso (es. soggetto "coral" e tag "rock")
        excluded -= terms
        return Relevance(terms, excluded)
    }
}
