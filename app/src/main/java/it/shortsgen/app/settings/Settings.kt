package it.shortsgen.app.settings

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    fun load(): AppSettings = AppSettings(
        textProvider = enumOrDefault(prefs.getString("text_provider", null), TextProviderType.GEMINI),
        imageProvider = enumOrDefault(prefs.getString("image_provider", null), ImageProviderType.PEXELS),
        geminiKey = secure.get("gemini_key"),
        groqKey = secure.get("groq_key"),
        pexelsKey = secure.get("pexels_key"),
        pixabayKey = secure.get("pixabay_key"),
        cloudflareAccountId = secure.get("cf_account"),
        cloudflareToken = secure.get("cf_token"),
        geminiTextModel = prefs.getString("gemini_text_model", null)
            ?.takeUnless { it == "gemini-2.5-flash" } // vecchio predefinito ritirato
            .orDefault(AppSettings.DEFAULT_GEMINI_TEXT_MODEL),
        groqModel = prefs.getString("groq_model", null).orDefault(AppSettings.DEFAULT_GROQ_MODEL),
        cloudflareModel = prefs.getString("cf_model", null).orDefault(AppSettings.DEFAULT_CLOUDFLARE_MODEL),
        speechRate = prefs.getFloat("speech_rate", 1.0f),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("text_provider", s.textProvider.name)
            .putString("image_provider", s.imageProvider.name)
            .putString("gemini_text_model", s.geminiTextModel.trim())
            .putString("groq_model", s.groqModel.trim())
            .putString("cf_model", s.cloudflareModel.trim())
            .putFloat("speech_rate", s.speechRate)
            .apply()
        secure.put("gemini_key", s.geminiKey)
        secure.put("groq_key", s.groqKey)
        secure.put("pexels_key", s.pexelsKey)
        secure.put("pixabay_key", s.pixabayKey)
        secure.put("cf_account", s.cloudflareAccountId)
        secure.put("cf_token", s.cloudflareToken)
    }

    private fun String?.orDefault(default: String) = if (this.isNullOrBlank()) default else this

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        runCatching { enumValueOf<T>(name ?: "") }.getOrDefault(default)
}
