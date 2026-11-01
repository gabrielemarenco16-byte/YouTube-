package it.shortsgen.app.image

/**
 * Scarta clip e foto astratte, animate o grafiche (render 3D, motion graphics, particelle…):
 * servono riprese girate dal vero. Si basa su titolo/tag forniti dal servizio.
 * Le parole che fanno parte del soggetto del video non vengono considerate (es. un video sui frattali).
 */
object GraphicFilter {
    private val WORDS = setOf(
        "abstract", "animation", "animated", "animate", "cartoon", "anime", "cgi", "3d", "render", "rendering",
        "rendered", "graphic", "graphics", "illustration", "illustrated", "infographic", "particle", "particles",
        "fractal", "fractals", "glitch", "hologram", "holographic", "kaleidoscope", "plexus", "vj", "logo",
        "intro", "overlay", "vector", "clipart", "drawing", "drawn", "sketch", "painting", "digital", "virtual",
        "simulation", "simulated", "generated", "futuristic", "cyberspace", "wireframe", "polygon", "polygonal",
    )
    private val PHRASES = listOf("motion graphics", "computer generated", "light leak", "lens flare", "screen saver", "screensaver")

    private fun words(text: String) = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    /** true se la descrizione indica un contenuto astratto/grafico/animato. */
    fun isGraphic(description: String, subject: String = ""): Boolean {
        val subjectWords = words(subject)
        val desc = description.lowercase()
        if (PHRASES.any { it in desc && subjectWords.none { s -> s in it } }) return true
        return words(description).any { it in WORDS && it !in subjectWords }
    }
}

/** Regole comuni per decidere se un file di Pexels/Pixabay è adatto. */
object ClipFilter {
    fun allowed(description: String, allowPeople: Boolean, subject: String = ""): Boolean =
        PeopleFilter.allowed(description, allowPeople) && !GraphicFilter.isGraphic(description, subject)
}
