# Shorts Generator

App Android (Kotlin + Jetpack Compose, Android 8.0+) che con **un solo pulsante** crea da sola uno
**YouTube Short di curiosità in inglese** (verticale 1080x1920, 30-60 secondi), con voce, immagini,
sottotitoli e copertina. L'interfaccia è in italiano, i contenuti dei video sono in inglese.

Tutto gira sul telefono: nessun server tuo, nessun computer. L'APK si compila con GitHub Actions.
L'app **non** carica niente su YouTube: il video lo pubblichi tu a mano.

---

## Come funziona

Premi **"Genera video"** e l'app esegue cinque fasi, mostrate nella barra di avanzamento e nella notifica:

| Fase | Cosa fa |
|------|---------|
| 1. Copione | Chiede a **Gemini** o **Groq** (a scelta) un JSON con titolo, descrizione, tag e 7 scene (testo narrato + descrizione dell'immagine). Gli argomenti già usati vengono salvati e scartati, così non si ripetono. |
| 2. Voce | Legge ogni scena con il **TextToSpeech di Android** (un file audio per scena). I tempi delle parole vengono stimati dai caratteri e dalla durata dell'audio. Se il video supera i 60 s la voce viene accelerata, se dura meno di 30 s le scene vengono allungate. |
| 3. Immagini | Genera un'immagine per scena con **Cloudflare Workers AI** o **Gemini**, con 3 tentativi. Se un'immagine non arriva usa un segnaposto colorato. |
| 4. Montaggio | **Media3 Transformer**: un'immagine per scena per tutta la durata dell'audio, zoom lento, dissolvenze dal nero, sottotitoli 3 parole alla volta (bianco/giallo con bordo nero) e audio sincronizzato. Il risultato è un MP4 H.264 + AAC. |
| 5. Copertina | Immagine 1280x720 con il titolo disegnato sopra. |

La generazione gira in un **servizio in primo piano** con notifica: continua anche se chiudi l'app.
Puoi annullarla dalla notifica. Se qualcosa va storto vedi un messaggio in italiano e il pulsante
**"Riprova"**, che riparte dal punto in cui si era fermato (copione, voce e immagini già pronti non
vengono rifatti).

Nella schermata **Risultato** trovi: anteprima del video, copertina, titolo/descrizione/tag con il
pulsante **"Copia"**, **"Salva in galleria"** (album *Shorts Generator* in Video e Immagini) e **"Condividi"**.

---

## 1. Ottenere le chiavi API gratuite

Ti servono **una chiave per il copione** e **una per le immagini**. La combinazione consigliata è
**Gemini (copione) + Cloudflare (immagini)**, oppure **Groq (copione) + Cloudflare (immagini)**.

### Gemini (Google AI Studio) – copione, e immagini se il tuo account le consente
1. Vai su <https://aistudio.google.com/apikey> e accedi con un account Google.
2. Premi **"Create API key"** (se richiesto, crea un progetto).
3. Copia la chiave (inizia con `AIza…`).

Modelli predefiniti nell'app: `gemini-flash-latest` (si aggiorna da solo all'ultimo modello Flash) per il testo e `gemini-2.5-flash-image` per le immagini.
I modelli e i limiti del piano gratuito di Google cambiano spesso: **la generazione di immagini
con Gemini potrebbe non essere inclusa nel piano gratuito**. Se ricevi errori 429 o 403 sulle immagini,
usa Cloudflare. Se un modello viene ritirato (errore 404) l'app chiede a Google l'elenco dei modelli disponibili, sceglie da sola quello più adatto e lo salva nelle Impostazioni; puoi comunque scriverne uno a mano
(elenco aggiornato su <https://ai.google.dev/gemini-api/docs/models>).

### Groq – copione (alternativa a Gemini)
1. Vai su <https://console.groq.com/keys> e registrati (gratis).
2. Premi **"Create API Key"** e copia la chiave (inizia con `gsk_…`).

Modello predefinito: `llama-3.3-70b-versatile` (elenco su <https://console.groq.com/docs/models>).

### Pollinations.ai – immagini senza chiave (predefinito)
Non serve nessuna registrazione: è già attivo. È gratuito ma può essere lento o avere limiti nelle ore
di punta. Se un altro servizio di immagini fallisce (chiave non valida o quota finita), l'app usa
automaticamente Pollinations come ultima riserva.

### Cloudflare Workers AI – immagini (qualità migliore)
Il piano gratuito include una quota giornaliera che basta per diversi video al giorno.
1. Registrati su <https://dash.cloudflare.com/sign-up>.
2. **Account ID**: nella dashboard apri **Workers AI** (menu *AI* → *Workers AI*) oppure guarda nella
   barra dell'indirizzo: è la stringa di 32 caratteri dopo `dash.cloudflare.com/`. Copiala.
3. **API Token**: vai su <https://dash.cloudflare.com/profile/api-tokens> → **Create Token** →
   modello **"Workers AI"** (oppure *Custom token* con permesso *Account → Workers AI → Read* e *Edit*)
   → **Continue to summary** → **Create Token**. Copia il token (viene mostrato una sola volta).

Modello predefinito: `@cf/black-forest-labs/flux-1-schnell`.

### Inserire le chiavi nell'app
Apri l'app → icona **ingranaggio** (Impostazioni) → scegli i servizi per *Copione* e *Immagini* →
incolla le chiavi → **Salva**. Le chiavi sono salvate **cifrate** sul telefono (AES-256-GCM con chiave
nell'Android Keystore) e non vengono incluse nei backup.

### Voce
L'app usa la sintesi vocale del telefono. Per la voce migliore installa/aggiorna **"Servizi di sintesi
vocale di Google"** dal Play Store e, in *Impostazioni Android → Sintesi vocale* (oppure dal pulsante nelle
Impostazioni dell'app), scarica la lingua **English (United States)**.

---

## 2. Far partire il workflow su GitHub Actions

Il file `.github/workflows/build.yml` esegue i test unitari e compila l'APK debug.

Parte **automaticamente a ogni push** su qualsiasi branch. Per avviarlo a mano:
1. Apri il repository su GitHub (anche dal browser del telefono, in modalità "sito desktop" se serve).
2. Vai nella scheda **Actions**.
3. A sinistra scegli **"Build APK"**.
4. Premi **"Run workflow"**, scegli il branch e conferma con **"Run workflow"**.

La compilazione dura circa 5-10 minuti. Un pallino verde ✅ vuol dire che è andata a buon fine.

---

## 3. Scaricare e installare l'APK

**Metodo più semplice:** ogni push aggiorna la pre-release **"debug-latest"**. Apri
<https://github.com/gabrielemarenco16-byte/YouTube-/releases/tag/debug-latest> dal telefono e tocca
**`ShortsGenerator-debug.apk`**: scarichi direttamente l'APK, senza zip. Poi passa al punto 4.

In alternativa, dagli artifact di Actions:

1. In **Actions** apri l'ultima esecuzione riuscita di **"Build APK"**.
2. In fondo alla pagina, nella sezione **Artifacts**, tocca **`ShortsGenerator-debug-apk`**.
   GitHub scarica un file **.zip** (serve essere loggati su GitHub).
3. Apri lo zip con l'app *File* del telefono ed estrai **`app-debug.apk`**.
4. Tocca l'APK. La prima volta Android chiede di **consentire l'installazione da questa fonte**
   (per il browser o l'app File che stai usando): attivala e torna indietro.
5. Premi **Installa**. Se Play Protect mostra un avviso per app sconosciuta, scegli
   *"Installa comunque"* (è normale per gli APK non pubblicati sul Play Store).
6. Al primo avvio consenti le **notifiche**, così vedi l'avanzamento anche ad app chiusa.

Gli APK sono tutti firmati con la stessa chiave di debug inclusa nel repository
(`app/debug.keystore`), quindi le nuove versioni si installano **sopra** quella vecchia senza
perdere impostazioni e chiavi.

---

## Problemi comuni

| Messaggio | Soluzione |
|-----------|-----------|
| *Chiave API non valida o senza permessi (401/403)* | Ricontrolla la chiave nelle Impostazioni (niente spazi). Per Cloudflare verifica Account ID e permessi del token. |
| *Limite di richieste gratuite raggiunto (429)* | Aspetta qualche minuto (o il giorno dopo) e premi **Riprova**. |
| *Modello non trovato (404) / richiesta rifiutata (400)* | Il modello è stato rinominato o ritirato: inserisci un nome aggiornato o premi *Ripristina modelli predefiniti*. |
| *La voce inglese non è installata* | Scarica la voce English (United States) come spiegato sopra. |
| *Il montaggio del video non è riuscito su questo telefono* | Riprova; chiudi altre app pesanti. L'app prova automaticamente anche un metodo alternativo (video e audio esportati separatamente e poi uniti). |
| Alcune immagini sono segnaposto colorati | Il servizio immagini non ha risposto: premi *Nuovo video* più tardi, oppure cambia servizio. |

---

## Struttura del progetto

```
app/src/main/java/it/shortsgen/app/
├── core/        errori in italiano, retry con attesa esponenziale, HTTP
├── script/      prompt, client Gemini/Groq (TextProvider), parser JSON, anti-ripetizione
├── voice/       VoiceProvider + TextToSpeech Android, utilità WAV, stima tempi parole
├── image/       ImageProvider + Gemini / Cloudflare, ritaglio 9:16, segnaposto
├── video/       timeline e sottotitoli, effetti zoom/dissolvenza, overlay, Media3 Transformer
├── thumbnail/   copertina 1280x720
├── settings/    impostazioni, archivio cifrato delle chiavi, cronologia argomenti
├── pipeline/    pipeline di generazione, stato condiviso, foreground service
└── ui/          schermate Home, Risultato, Impostazioni (Compose)
```

`VoiceProvider`, `ImageProvider` e `TextProvider` sono interfacce: per aggiungere un nuovo servizio basta
scrivere una nuova implementazione e selezionarla in `GenerationPipeline`.

### Test
I test unitari (copione, anti-ripetizione, client HTTP con MockWebServer, WAV, tempi delle parole,
sottotitoli, effetti, durata, errori, retry) girano nel workflow prima della compilazione.
Per eseguirli in locale: `./gradlew testDebugUnitTest`.
