package it.shortsgen.app.settings

import it.shortsgen.app.image.PixabayApi

enum class TextProviderType(val label: String) { GEMINI("Gemini"), GROQ("Groq") }

enum class ImageProviderType(val label: String) {
    PEXELS("Pexels – video e foto reali (consigliato)"),
    PIXABAY("Pixabay – video e foto reali"),
    CLOUDFLARE("Cloudflare Workers AI – immagini AI"),
}

data class AppSettings(
    val textProvider: TextProviderType = TextProviderType.GEMINI,
    val imageProvider: ImageProviderType = ImageProviderType.PEXELS,
    val geminiKey: String = "",
    val groqKey: String = "",
    val pexelsKey: String = "",
    val pixabayKey: String = "",
    val cloudflareAccountId: String = "",
    val cloudflareToken: String = "",
    val geminiTextModel: String = DEFAULT_GEMINI_TEXT_MODEL,
    val groqModel: String = DEFAULT_GROQ_MODEL,
    val cloudflareModel: String = DEFAULT_CLOUDFLARE_MODEL,
    val speechRate: Float = 1.0f,
) {
    /** Una chiave Pixabay incollata per errore nel campo Pexels viene riconosciuta e usata per Pixabay. */
    val pixabayKeyInPexelsField: Boolean get() = PixabayApi.looksLikeKey(pexelsKey)

    val effectivePexelsKey: String get() = if (pixabayKeyInPexelsField) "" else pexelsKey.trim()

    val effectivePixabayKey: String
        get() = pixabayKey.trim().ifEmpty { if (pixabayKeyInPexelsField) pexelsKey.trim() else "" }

    val hasCloudflare: Boolean get() = cloudflareAccountId.isNotBlank() && cloudflareToken.isNotBlank()

    private fun configured(type: ImageProviderType) = when (type) {
        ImageProviderType.PEXELS -> effectivePexelsKey.isNotEmpty()
        ImageProviderType.PIXABAY -> effectivePixabayKey.isNotEmpty()
        ImageProviderType.CLOUDFLARE -> hasCloudflare
    }

    /**
     * Servizi immagini da provare, in ordine: quello scelto, poi gli altri configurati.
     * Se nessuno è configurato resta quello scelto (per mostrare quale chiave manca).
     */
    fun imageSources(): List<ImageProviderType> =
        (listOf(imageProvider) + ImageProviderType.entries).distinct().filter(::configured).ifEmpty { listOf(imageProvider) }

    /** Elenco delle informazioni mancanti per poter generare un video. */
    fun missing(): List<String> = buildList {
        when (textProvider) {
            TextProviderType.GEMINI -> if (geminiKey.isBlank()) add("GEMINI_API_KEY (chiave Gemini per il copione)")
            TextProviderType.GROQ -> if (groqKey.isBlank()) add("GROQ_API_KEY (chiave Groq per il copione)")
        }
        if (ImageProviderType.entries.none(::configured)) {
            when (imageProvider) {
                ImageProviderType.PEXELS -> add("PEXELS_API_KEY (chiave Pexels per video e foto)")
                ImageProviderType.PIXABAY -> add("PIXABAY_API_KEY (chiave Pixabay per video e foto)")
                ImageProviderType.CLOUDFLARE -> {
                    if (cloudflareAccountId.isBlank()) add("CF_ACCOUNT_ID (Account ID Cloudflare)")
                    if (cloudflareToken.isBlank()) add("CF_API_TOKEN (token API Cloudflare)")
                }
            }
        }
    }.distinct()

    companion object {
        const val DEFAULT_GEMINI_TEXT_MODEL = "gemini-flash-latest"
        const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_CLOUDFLARE_MODEL = "@cf/black-forest-labs/flux-1-schnell"
    }
}
