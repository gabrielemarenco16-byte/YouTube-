package it.shortsgen.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import it.shortsgen.app.settings.AppSettings
import it.shortsgen.app.settings.ImageProviderType
import it.shortsgen.app.settings.SettingsRepository
import it.shortsgen.app.settings.TextProviderType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    var s by remember { mutableStateOf(repo.load()) }

    fun save() {
        repo.save(s)
        Toast.makeText(context, "Impostazioni salvate", Toast.LENGTH_SHORT).show()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = { TextButton(onClick = { save() }) { Text("Salva") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Copione (testo)") {
                TextProviderType.entries.forEach { type ->
                    RadioRow(type.label, selected = s.textProvider == type) { s = s.copy(textProvider = type) }
                }
                when (s.textProvider) {
                    TextProviderType.GEMINI -> ModelField("Modello Gemini (testo)", s.geminiTextModel) {
                        s = s.copy(geminiTextModel = it)
                    }
                    TextProviderType.GROQ -> ModelField("Modello Groq", s.groqModel) { s = s.copy(groqModel = it) }
                }
            }

            Section("Immagini") {
                ImageProviderType.entries.forEach { type ->
                    RadioRow(type.label, selected = s.imageProvider == type) { s = s.copy(imageProvider = type) }
                }
                when (s.imageProvider) {
                    ImageProviderType.GEMINI -> ModelField("Modello Gemini (immagini)", s.geminiImageModel) {
                        s = s.copy(geminiImageModel = it)
                    }
                    ImageProviderType.CLOUDFLARE -> ModelField("Modello Cloudflare", s.cloudflareModel) {
                        s = s.copy(cloudflareModel = it)
                    }
                    ImageProviderType.POLLINATIONS -> Text(
                        "Gratis e senza chiave. Può essere lento o avere limiti nelle ore di punta.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Section("Chiavi API (salvate cifrate sul telefono)") {
                SecretField("Chiave API Gemini", s.geminiKey) { s = s.copy(geminiKey = it) }
                SecretField("Chiave API Groq", s.groqKey) { s = s.copy(groqKey = it) }
                SecretField("Cloudflare Account ID", s.cloudflareAccountId, secret = false) {
                    s = s.copy(cloudflareAccountId = it)
                }
                SecretField("Cloudflare API Token", s.cloudflareToken) { s = s.copy(cloudflareToken = it) }
                Text(
                    "Servono solo le chiavi dei servizi selezionati sopra. Se un servizio di immagini non funziona, " +
                        "l'app prova da sola gli altri configurati e infine Pollinations (senza chiave).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Voce") {
                Text("Velocità del parlato: ${String.format(Locale.ITALY, "%.2f", s.speechRate)}x")
                Slider(
                    value = s.speechRate,
                    onValueChange = { s = s.copy(speechRate = (it * 20).toInt() / 20f) },
                    valueRange = 0.8f..1.4f,
                )
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure {
                        Toast.makeText(context, "Apri Impostazioni > Sintesi vocale dal telefono", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Apri impostazioni sintesi vocale di Android") }
            }

            TextButton(onClick = {
                s = s.copy(
                    geminiTextModel = AppSettings.DEFAULT_GEMINI_TEXT_MODEL,
                    groqModel = AppSettings.DEFAULT_GROQ_MODEL,
                    geminiImageModel = AppSettings.DEFAULT_GEMINI_IMAGE_MODEL,
                    cloudflareModel = AppSettings.DEFAULT_CLOUDFLARE_MODEL,
                )
            }) { Text("Ripristina modelli predefiniti") }

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Salva") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

@Composable
private fun ModelField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretField(label: String, value: String, secret: Boolean = true, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(!secret) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii),
        trailingIcon = if (secret) {
            { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Nascondi" else "Mostra") } }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
