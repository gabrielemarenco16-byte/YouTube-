package it.shortsgen.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.shortsgen.app.BuildConfig
import it.shortsgen.app.pipeline.GenerationService
import it.shortsgen.app.pipeline.GenerationState
import it.shortsgen.app.pipeline.Phase
import it.shortsgen.app.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: GenerationState,
    hasLastResult: Boolean,
    onOpenSettings: () -> Unit,
    onOpenResult: () -> Unit,
) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(emptyList<String>()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Ricontrolla le chiavi ogni volta che si torna sulla schermata.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            missing = SettingsRepository(context).load().missing()
        }
    }
    var pendingRetry by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // La generazione parte comunque: senza permesso manca solo la notifica.
        GenerationService.start(context, retry = pendingRetry)
    }

    fun start(retry: Boolean) {
        pendingRetry = retry
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GenerationService.start(context, retry)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shorts Generator") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Impostazioni")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Crea uno YouTube Short di curiosità in inglese con un solo tocco.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            if (missing.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Configurazione incompleta", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Manca: " + missing.joinToString(", ") + ".")
                        TextButton(onClick = onOpenSettings) { Text("Apri Impostazioni") }
                    }
                }
            }

            val running = state is GenerationState.Running
            Button(
                onClick = { start(retry = false) },
                enabled = !running && missing.isEmpty(),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("Generazione in corso…", fontSize = 20.sp)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Genera video", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }

            when (state) {
                is GenerationState.Running -> ProgressCard(state, onCancel = { GenerationService.cancel(context) })
                is GenerationState.Failed -> ErrorCard(
                    state = state,
                    onRetry = { start(retry = true) },
                    onNew = { start(retry = false) },
                    enabled = missing.isEmpty(),
                )
                else -> Unit
            }

            if (hasLastResult && !running) {
                OutlinedButton(onClick = onOpenResult, modifier = Modifier.fillMaxWidth()) {
                    Text("Apri l'ultimo video creato")
                }
            }

            Text(
                "La generazione continua anche se chiudi l'app: segui l'avanzamento nella notifica.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                "Versione ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ProgressCard(state: GenerationState.Running, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Fase ${state.phase.number} di ${Phase.COUNT}: ${state.phase.label}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${(state.progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
            }
            PhaseSteps(current = state.phase)
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Annulla") }
        }
    }
}

@Composable
private fun PhaseSteps(current: Phase) {
    Row(Modifier.fillMaxWidth()) {
        Phase.entries.forEach { p ->
            val color = when {
                p.ordinal < current.ordinal -> MaterialTheme.colorScheme.secondary
                p == current -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }
            val mark = if (p.ordinal < current.ordinal) "✓ " else ""
            Text(
                mark + p.label,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ErrorCard(state: GenerationState.Failed, onRetry: () -> Unit, onNew: () -> Unit, enabled: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Qualcosa è andato storto" + (state.phase?.let { " (fase: ${it.label})" } ?: ""),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(state.message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, enabled = enabled) { Text("Riprova") }
                OutlinedButton(onClick = onNew, enabled = enabled) { Text("Nuovo video") }
            }
            Text(
                "\"Riprova\" riparte dal punto in cui si era fermato, senza rifare i passaggi già completati.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
