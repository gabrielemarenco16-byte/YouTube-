package it.shortsgen.app.settings

import android.content.Context
import it.shortsgen.app.script.TopicStore
import org.json.JSONArray

/** Argomenti già usati, salvati sul telefono per non ripeterli. */
class TopicHistory(context: Context) : TopicStore {
    private val prefs = context.getSharedPreferences("topic_history", Context.MODE_PRIVATE)

    @Synchronized
    override fun all(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun add(topic: String) {
        val updated = (all() + topic.trim()).filter { it.isNotEmpty() }.distinct().takeLast(MAX)
        prefs.edit().putString(KEY, JSONArray(updated).toString()).apply()
    }

    private companion object {
        const val KEY = "topics"
        const val MAX = 300
    }
}
