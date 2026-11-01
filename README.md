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
| 1. Copione | Chiede a **Gemini** o **Groq** (a scelta) un JSON con titolo, descrizione, tag e 7 scene (testo narrato + descrizione dell'immagine). Per ogni scena anche 2-4 parole chiave inglesi per cercare il video. Gli argomenti già usati vengono salvati e scartati, così non si ripetono. |
| 2. Voce | Legge ogni scena con il **TextToSpeech di Android** (un file audio per scena). L'app rileva nel file audio dove inizia e finisce la voce e distribuisce le parole in proporzione ai caratteri. Se il video supera i 60 s la voce viene accelerata, se dura meno di 30 s le scene vengono allungate. |
| 3. Immagini e video | Il copione indica il **soggetto principale** del video (es. "jellyfish"), che viene aggiunto a **ogni** ricerca, così nessuna scena mostra immagini estranee. Per ogni scena l'LLM scrive 3 ricerche visive concrete (oggetti, luoghi, azioni) e il copione ha un tema (es. "chemistry", "fire", "space"). Le clip e foto con persone vengono scartate (in base a titolo e tag), a meno che il copione parli di persone. Per la prima scena (hook) l'app usa la ricerca più specifica, scarica 3 clip e tiene quella con più movimento. Se per una scena non c'è nessuna clip né foto del soggetto, riusa un tratto diverso della clip di un'altra scena (mai una clip generica). L'app prova le 3 ricerche in ordine, poi alcune ricerche generiche del tema (es. "tree", "forest", "seed pod", "plant"), cercando clip video su **Pexels** o **Pixabay**; se non trova video adatti, usa le foto. Se proprio non trova nulla crea una scheda grafica pulita (sfondo scuro, un cerchio colorato e la parola chiave della scena in grande) e lo segnala. Ogni scena registra nei log (tag `ShortsMedia`) e nella schermata del risultato la ricerca usata e il file scelto. Lo stesso file non viene mai usato due volte nello stesso video. In alternativa (o come riserva) **Cloudflare Workers AI** genera un'immagine. 3 tentativi con pause per ogni servizio; se uno fallisce si prova l'altro; solo alla fine un segnaposto, con avviso nei log e a schermo. |
| 4. Montaggio | **Media3 Transformer**: per ogni scena la clip o l'immagine ritagliata in 9:16 a 1080x1920 (zoom lento sulle immagini), dissolvenze dal nero, voce sincronizzata e **solo i sottotitoli in basso**: tutte le parole del copione, in ordine, divise per frasi e dopo le virgole, massimo 3 per riga e mai una parola da sola; il carattere ha sempre la stessa dimensione e, se serve, il testo va su due righe (bianco/giallo con bordo nero). L'ultima scena dura quanto il suo audio più mezzo secondo, così la frase finale non viene tagliata. Il risultato è un MP4 H.264 + AAC. |
| 5. Copertina | Immagine 1280x720 con il titolo disegnato sopra. |

La generazione gira in un **servizio in primo piano** con notifica: continua anche se chiudi l'app.
Puoi annullarla dalla notifica. Se qualcosa va storto vedi un messaggio in italiano e il pulsante
**"Riprova"**, che riparte dal punto in cui si era fermato (copione, voce e immagini già pronti non
vengono rifatti).

Nella schermata **Risultato** trovi: anteprima del video, l'elenco delle scene con ricerca e file usati e il pulsante **"Cambia clip"** (cerca un'altra clip con la stessa ricerca e rifà solo il montaggio, senza rigenerare copione e voce), copertina, titolo/descrizione/tag con il
pulsante **"Copia"**, **"Salva in galleria"** (album *Shorts Generator* in Video e Immagini) e **"Condividi"**.

---

## 1. Ottenere le chiavi API gratuite

Ti servono **una chiave per il copione** (Gemini oppure Groq) e **una per le immagini** (Pexels,
consigliato, oppure Cloudflare). Ogni chiave ha un nome di variabile, che trovi uguale nelle Impostazioni
dell'app, nei messaggi di errore e nei secrets di GitHub:

| Variabile | Servizio | Serve per |
|-----------|----------|-----------|
| `GEMINI_API_KEY` | Google AI Studio | copione (predefinito) |
| `GROQ_API_KEY` | Groq | copione (alternativa) |
| `PEXELS_API_KEY` | Pexels | clip video e foto (predefinito) |
| `PIXABAY_API_KEY` | Pixabay | clip video e foto (alternativa a Pexels) |
| `CF_ACCOUNT_ID` + `CF_API_TOKEN` | Cloudflare Workers AI | immagini AI (opzionale, anche come riserva) |

**Le chiavi non sono mai scritte nel codice.** Nell'app si inseriscono nelle Impostazioni (salvate
cifrate sul telefono); su GitHub si usano solo come *secrets* per il test automatico.

### Gemini (Google AI Studio) – `GEMINI_API_KEY`
1. Vai su <https://aistudio.google.com/apikey> e accedi con un account Google.
2. Premi **"Create API key"** (se richiesto, crea un progetto) e copia la chiave.

Modello predefinito: `gemini-flash-latest` (si aggiorna da solo all'ultimo modello Flash). Se un modello
viene ritirato (404) o è sovraccarico (503), l'app sceglie da sola un altro modello disponibile.

### Groq – `GROQ_API_KEY` (alternativa a Gemini)
1. Vai su <https://console.groq.com/keys> e registrati (gratis).
2. Premi **"Create API Key"** e copia la chiave (inizia con `gsk_…`).

Modello predefinito: `llama-3.3-70b-versatile`.

### Pexels – `PEXELS_API_KEY` (clip video e foto, consigliato)
Gratis, senza watermark, video reali in verticale.
1. Registrati su <https://www.pexels.com/join/>.
2. Vai su <https://www.pexels.com/api/> → **"Your API Key"** (o *Get Started*), compila il breve modulo
   (scopo: progetto personale) e copia la chiave.

Limiti gratuiti: 200 richieste all'ora, 20.000 al mese (un video ne usa circa 10-20).

### Pixabay – `PIXABAY_API_KEY` (alternativa a Pexels)
Gratis, senza watermark. Registrati su <https://pixabay.com/accounts/register/>, poi apri
<https://pixabay.com/api/docs/>: la tua chiave è scritta nella sezione *Parameters* (formato `12345678-abc…`).
Se per errore la incolli nel campo Pexels, l'app la riconosce e la usa per Pixabay.

### Cloudflare Workers AI – `CF_ACCOUNT_ID` e `CF_API_TOKEN` (opzionale)
Il piano gratuito include una quota giornaliera che basta per diversi video al giorno.
1. Registrati su <https://dash.cloudflare.com/sign-up>.
2. **CF_ACCOUNT_ID**: nella dashboard apri **Workers AI** (menu *AI* → *Workers AI*) oppure guarda nella
   barra dell'indirizzo: è la stringa di 32 caratteri dopo `dash.cloudflare.com/`.
3. **CF_API_TOKEN**: vai su <https://dash.cloudflare.com/profile/api-tokens> → **Create Token** →
   accanto al modello **"Workers AI"** premi **"Use template"** → **Continue to summary** → **Create Token**.
   Copia tutto il token (viene mostrato una sola volta).

Modello: `@cf/black-forest-labs/flux-1-schnell`.

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

### Test automatico end-to-end (video di prova su emulatore)

Il workflow **"E2E video test"** (`.github/workflows/e2e.yml`) avvia un emulatore Android, genera un video
vero con l'app, estrae un fotogramma a metà di ogni scena e alla fine del video, e controlla:
nessuna scena con segnaposto, nessun file ripetuto, sottotitoli con tutte le parole del copione
nell'ordine giusto, durata del video uguale alla voce più la pausa finale.
I risultati (fotogrammi, `report.txt`, video) vengono pubblicati nella pre-release **"e2e-latest"**.

Per usare le tue chiavi aggiungile come secrets del repository: su GitHub apri **Settings → Secrets and
variables → Actions → New repository secret** e crea `GEMINI_API_KEY` (o `GROQ_API_KEY`) e
`PEXELS_API_KEY` o `PIXABAY_API_KEY` (opzionali `CF_ACCOUNT_ID` e `CF_API_TOKEN`). Senza chiavi il test usa un copione di
prova e immagini segnaposto, e il controllo "nessun segnaposto" risulta fallito.

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
| *chiave mancante o non valida (401/403). Controlla PEXELS_API_KEY…* | Il messaggio indica la variabile da controllare: aprila nelle Impostazioni e incolla di nuovo la chiave (niente spazi). Per Cloudflare verifica `CF_ACCOUNT_ID` e che `CF_API_TOKEN` sia creato con il modello "Workers AI". |
| *Limite di richieste gratuite raggiunto (429)* | Aspetta qualche minuto (o il giorno dopo) e premi **Riprova**. |
| *Modello non trovato (404) / richiesta rifiutata (400)* | Il modello è stato rinominato o ritirato: inserisci un nome aggiornato o premi *Ripristina modelli predefiniti*. |
| *La voce inglese non è installata* | Scarica la voce English (United States) come spiegato sopra. |
| *Il montaggio del video non è riuscito su questo telefono* | Riprova; chiudi altre app pesanti. L'app prova automaticamente anche un metodo alternativo (video e audio esportati separatamente e poi uniti). |
| *ATTENZIONE: scena N senza immagine (segnaposto)* | Né Pexels né Cloudflare hanno fornito un file per quella scena: la causa è scritta nell'avviso. Premi *Nuovo video* più tardi, oppure configura anche l'altro servizio come riserva. |

---

## Struttura del progetto

```
app/src/main/java/it/shortsgen/app/
├── core/        errori in italiano, retry con attesa esponenziale, HTTP
├── script/      prompt, client Gemini/Groq (TextProvider), parser JSON, anti-ripetizione
├── voice/       VoiceProvider + TextToSpeech Android, utilità WAV, stima tempi parole
├── image/       ImageProvider + Pexels (video/foto) / Cloudflare, scelta per scena, segnaposto
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
