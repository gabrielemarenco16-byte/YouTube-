package it.shortsgen.app.pipeline

import android.content.Context
import it.shortsgen.app.core.Http
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cartelle di lavoro dei video in files/jobs/<data>. Ogni passaggio salva i propri file,
 * così "Riprova" riparte dal punto in cui si era fermato.
 */
class JobStore(private val context: Context) {
    private val root = File(context.filesDir, "jobs").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("jobs", Context.MODE_PRIVATE)

    fun newJob(): File {
        cleanup()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(root, name).apply { mkdirs() }
        prefs.edit().putString(KEY_CURRENT, dir.name).apply()
        return dir
    }

    /** Ultimo lavoro non completato (per "Riprova"), se esiste ancora. */
    fun currentJob(): File? {
        val name = prefs.getString(KEY_CURRENT, null) ?: return null
        return File(root, name).takeIf { it.isDirectory }
    }

    fun saveResult(result: GenerationResult) {
        File(result.jobDir, RESULT_FILE).writeText(Http.json.encodeToString(GenerationResult.serializer(), result))
        prefs.edit().putString(KEY_LAST_RESULT, File(result.jobDir).name).apply()
    }

    fun loadLastResult(): GenerationResult? {
        val name = prefs.getString(KEY_LAST_RESULT, null) ?: return null
        val file = File(File(root, name), RESULT_FILE)
        if (!file.exists()) return null
        return runCatching { Http.json.decodeFromString(GenerationResult.serializer(), file.readText()) }
            .getOrNull()
            ?.takeIf { File(it.videoPath).exists() }
    }

    /** Tiene solo gli ultimi lavori per non riempire la memoria del telefono. */
    private fun cleanup() {
        val keep = setOfNotNull(prefs.getString(KEY_LAST_RESULT, null))
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()
        dirs.drop(KEEP).filter { it.name !in keep }.forEach { it.deleteRecursively() }
    }

    companion object {
        const val RESULT_FILE = "result.json"
        private const val KEY_CURRENT = "current_job"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEEP = 2
    }
}
