package it.shortsgen.app.image

import it.shortsgen.app.core.Http
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Memoria tra i video: ID delle clip/foto già usate (file JSON), per dare priorità
 * a quelle mai usate. Si tengono solo gli ultimi [maxEntries].
 */
class ClipHistory(private val file: File, private val maxEntries: Int = 3000) {

    @Serializable
    data class Entry(val id: String, val usedAt: Long)

    @Serializable
    private data class Data(val clips: List<Entry> = emptyList())

    @Synchronized
    fun ids(): Set<String> = read().clips.map { it.id }.toSet()

    @Synchronized
    fun add(ids: Collection<String>, now: Long = System.currentTimeMillis()) {
        val clean = ids.filter { it.isNotBlank() && isStockId(it) }
        if (clean.isEmpty()) return
        val kept = read().clips.filter { it.id !in clean }
        val updated = (kept + clean.distinct().map { Entry(it, now) }).takeLast(maxEntries)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Http.json.encodeToString(Data.serializer(), Data(updated)))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun read(): Data =
        runCatching { Http.json.decodeFromString(Data.serializer(), file.readText()) }.getOrDefault(Data())

    companion object {
        const val FILE_NAME = "used_clips.json"

        /** Solo i file dei servizi di repertorio (non schede grafiche né immagini AI). */
        fun isStockId(id: String) = id.startsWith("pexels-") || id.startsWith("pixabay-")
    }
}
