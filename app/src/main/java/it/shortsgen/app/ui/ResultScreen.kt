package it.shortsgen.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.shortsgen.app.pipeline.GenerationRepository
import it.shortsgen.app.pipeline.GenerationService
import it.shortsgen.app.pipeline.GenerationState
import it.shortsgen.app.pipeline.GenerationResult
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(result: GenerationResult, onBack: () -> Unit) {
    val context = LocalContext.current
    val videoFile = remember(result) { File(result.videoPath) }
    val thumbFile = remember(result) { File(result.thumbnailPath) }
    val tagsText = remember(result) { result.tags.joinToString(", ") }
    val state by GenerationRepository.state.collectAsStateWithLifecycle()
    val notice by GenerationRepository.notice.collectAsStateWithLifecycle()
    val busy = state is GenerationState.Running

    fun saveAll() {
        val videoOk = MediaActions.saveToGallery(context, videoFile, isVideo = true)
        val thumbOk = thumbFile.exists() && MediaActions.saveToGallery(context, thumbFile, isVideo = false)
        val msg = when {
            videoOk && thumbOk -> "Video e copertina salvati in Galleria (album \"Shorts Generator\")"
            videoOk -> "Video salvato in Galleria (la copertina non è stata salvata)"
            else -> "Impossibile salvare in Galleria. Controlla lo spazio libero e i permessi."
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveAll() else Toast.makeText(context, "Permesso di archiviazione negato", Toast.LENGTH_LONG).show()
    }

    fun onSaveClick() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else saveAll()
    }

    fun copy(label: String, text: String) {
        MediaActions.copyToClipboard(context, label, text)
        Toast.makeText(context, "$label copiato", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video pronto") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            (state as? GenerationState.Running)?.let { running ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sto rimontando il video…", fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { running.progress }, modifier = Modifier.fillMaxWidth())
                        Text(running.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            notice?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { GenerationRepository.setNotice(null) }) { Text("OK") }
                    }
                }
            }
            if (result.warnings.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        "Attenzione: " + result.warnings.joinToString(" "),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .align(Alignment.CenterHorizontally),
            ) {
                if (videoFile.exists() && !busy) {
                    key(result.createdAt) { VideoPreview(videoFile) }
                } else {
                    Text("File video non trovato", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
            Text("Durata: ${result.durationSec} secondi", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onSaveClick() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Salva in galleria") }
                OutlinedButton(
                    onClick = {
                        MediaActions.share(context, videoFile, "video/mp4", result.title)
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Condividi") }
            }

            Text("Copertina (1280x720)", fontWeight = FontWeight.Bold)
            val thumb = remember(result.createdAt) {
                runCatching { BitmapFactory.decodeFile(thumbFile.absolutePath)?.asImageBitmap() }.getOrNull()
            }
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = "Copertina",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp)),
                )
                TextButton(onClick = { MediaActions.share(context, thumbFile, "image/jpeg", result.title) }) {
                    Text("Condividi copertina")
                }
            }

            CopyField("Titolo", result.title, onCopy = ::copy)
            CopyField("Descrizione", result.description, onCopy = ::copy)
            CopyField("Tag", tagsText, onCopy = ::copy)
            if (result.sceneLog.isNotEmpty()) {
                Text("Scene", fontWeight = FontWeight.Bold)
                result.sceneLog.forEachIndexed { index, line ->
                    SceneRow(index, line, enabled = !busy) { GenerationService.replaceClip(context, index) }
                }
                CopyField("Dettagli scene (query e file)", result.sceneLog.joinToString("\n"), onCopy = ::copy)
            }
        }
    }
}

/** Una scena: riassunto di ramo/query/file e pulsante per cambiare la clip. */
@Composable
private fun SceneRow(index: Int, logLine: String, enabled: Boolean, onReplace: () -> Unit) {
    val parts = logLine.split(" | ")
    val summary = parts.drop(1).filter { p ->
        p.startsWith("ramo:") || p.startsWith("query usata:") || p.startsWith("parola:") || p.startsWith("file:")
    }.joinToString("\n")
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scena ${index + 1}", fontWeight = FontWeight.Bold)
                Text(summary.ifEmpty { logLine }, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onReplace, enabled = enabled) { Text("Cambia clip") }
        }
    }
}

@Composable
private fun CopyField(label: String, value: String, onCopy: (String, String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onCopy(label, value) }) { Text("Copia") }
            }
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun VideoPreview(file: File) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            VideoView(ctx).apply {
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(Uri.fromFile(file))
                setOnPreparedListener { player ->
                    player.isLooping = true
                    start()
                }
            }
        },
        onRelease = { it.stopPlayback() },
    )
}
