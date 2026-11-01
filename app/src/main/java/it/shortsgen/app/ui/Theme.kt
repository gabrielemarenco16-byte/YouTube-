package it.shortsgen.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFFF3D57)
private val Indigo = Color(0xFF4F46E5)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Accent, secondary = Color(0xFF818CF8), tertiary = Color(0xFFFFD60A))
    } else {
        lightColorScheme(primary = Accent, secondary = Indigo, tertiary = Color(0xFFB45309))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
