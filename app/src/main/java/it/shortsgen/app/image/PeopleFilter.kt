package it.shortsgen.app.image

/**
 * Esclude clip e foto con persone in primo piano, a meno che il copione parli di persone.
 * Si basa sulla descrizione fornita dal servizio (titolo, tag, testo alternativo).
 */
object PeopleFilter {
    private val PEOPLE = setOf(
        "person", "persons", "people", "man", "men", "woman", "women", "girl", "girls", "boy", "boys",
        "child", "children", "kid", "kids", "baby", "lady", "guy", "model", "portrait", "face", "faces",
        "selfie", "couple", "family", "crowd", "teen", "teenager", "human", "humans", "businessman",
        "businesswoman", "student", "students", "doctor", "worker", "athlete", "dancer", "tourist", "smiling",
        "posing", "fashion", "bride", "groom", "mother", "father", "friends", "diver", "scientist", "surfer",
    )

    /** Parole del testo, con anche il singolare dei plurali regolari ("workers" → "worker"). */
    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            .flatMap { w -> if (w.length > 4 && w.endsWith("s")) listOf(w, w.dropLast(1)) else listOf(w) }
            .toSet()

    /** true se il copione nomina persone: in quel caso le clip con persone sono ammesse. */
    fun scriptMentionsPeople(text: String): Boolean = words(text).any { it in PEOPLE }

    /** true se la descrizione indica persone (quindi da scartare quando non ammesse). */
    fun showsPeople(description: String): Boolean = words(description).any { it in PEOPLE }

    /** true se il file è ammesso. */
    fun allowed(description: String, allowPeople: Boolean): Boolean = allowPeople || !showsPeople(description)
}
