package it.shortsgen.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.shortsgen.app.pipeline.GenerationRepository
import it.shortsgen.app.pipeline.GenerationState

enum class Screen { HOME, RESULT, SETTINGS }

@Composable
fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val state by GenerationRepository.state.collectAsStateWithLifecycle()
    val lastResult by GenerationRepository.lastResult.collectAsStateWithLifecycle()

    // A generazione completata si passa direttamente al risultato.
    LaunchedEffect(state) {
        if (state is GenerationState.Success) {
            screen = Screen.RESULT
            GenerationRepository.reset()
        }
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = state,
            hasLastResult = lastResult != null,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenResult = { screen = Screen.RESULT },
        )
        Screen.RESULT -> {
            val result = lastResult
            if (result == null) {
                LaunchedEffect(Unit) { screen = Screen.HOME }
            } else {
                ResultScreen(result = result, onBack = { screen = Screen.HOME })
            }
        }
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
    }
}
