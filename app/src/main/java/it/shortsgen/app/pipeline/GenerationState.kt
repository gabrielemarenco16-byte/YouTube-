package it.shortsgen.app.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

enum class Phase(val label: String, val weight: Float) {
    SCRIPT("Copione", 0.10f),
    VOICE("Voce", 0.15f),
    IMAGES("Immagini", 0.35f),
    VIDEO("Montaggio", 0.35f),
    THUMBNAIL("Copertina", 0.05f);

    val number: Int get() = ordinal + 1

    /** Avanzamento complessivo (0..1) a partire da quello della fase. */
    fun overall(phaseProgress: Float): Float {
        val start = STARTS[ordinal]
        val end = STARTS[ordinal + 1]
        return (start + (end - start) * phaseProgress.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    companion object {
        val COUNT = entries.size

        /** Inizio cumulativo di ogni fase (l'ultimo valore è la fine). */
        private val STARTS: FloatArray by lazy {
            val total = entries.sumOf { it.weight.toDouble() }
            val arr = FloatArray(entries.size + 1)
            var acc = 0.0
            entries.forEachIndexed { i, ph ->
                acc += ph.weight
                arr[i + 1] = (acc / total).toFloat()
            }
            arr
        }
    }
}

@Serializable
data class GenerationResult(
    val jobDir: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val videoPath: String,
    val thumbnailPath: String,
    val durationSec: Int,
    val warnings: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

sealed interface GenerationState {
    data object Idle : GenerationState

    data class Running(
        val phase: Phase,
        val progress: Float,
        val detail: String,
    ) : GenerationState

    data class Success(val result: GenerationResult) : GenerationState

    data class Failed(val message: String, val phase: Phase?) : GenerationState
}

/** Stato condiviso tra il servizio (che genera) e l'interfaccia (che mostra). */
object GenerationRepository {
    private val _state = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val state: StateFlow<GenerationState> = _state.asStateFlow()

    private val _lastResult = MutableStateFlow<GenerationResult?>(null)
    val lastResult: StateFlow<GenerationResult?> = _lastResult.asStateFlow()

    fun update(state: GenerationState) {
        _state.value = state
        if (state is GenerationState.Success) _lastResult.value = state.result
    }

    fun setLastResult(result: GenerationResult?) {
        _lastResult.value = result
    }

    fun reset() {
        if (_state.value !is GenerationState.Running) _state.value = GenerationState.Idle
    }

    val isRunning: Boolean get() = _state.value is GenerationState.Running
}
