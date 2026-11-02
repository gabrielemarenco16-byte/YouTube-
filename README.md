# YouTube-

## Martingala 3 Fasi (Expert Advisor MetaTrader 5)

File: [`MQL5/Experts/MartingalaTreFasi.mq5`](MQL5/Experts/MartingalaTreFasi.mq5)

Martingala a griglia che cambia la distanza tra gli ordini e lo stop loss
in base a quanto è ampio il mercato (il range tra il massimo e il minimo
delle ultime candele, di default le ultime 24 candele H1).

### Le tre fasi

| Fase | Quando | Distanza tra gli ordini |
|---|---|---|
| **1 – Laterale** | range ≤ 300 pips | 30 pips |
| **2 – Allargamento** | range tra 300 e 2000 pips | 150 pips, oppure range ÷ 8 se è più grande (a 2000 pips sono 250 pips) |
| **3 – Stop** | range ≥ 2000 pips | nessun nuovo ordine |

Lo **stop loss è spento di default**, per vedere nel tester la martingala
senza blocchi di perdita. Si riattiva dagli input (es. 500 point in fase 1 e
650 point in fase 2).

Durante un ciclo il range conta tutto quello che è successo dall'inizio del
ciclo, quindi la fase può solo salire (1 → 2 → 3). Così l'EA non torna alla
griglia stretta da 30 pips mentre è già in perdita.

### Come funziona un ciclo

1. **Ingresso (scalper)**: appena non c'è un ciclo aperto entra subito.
   Guarda l'ultima ora (12 candele M5): compra se il prezzo è nella metà
   bassa, vende se è nella metà alta. Con la zona sotto il 50% entra solo
   vicino ai bordi (es. 30% = solo nel 30% basso o alto). Puoi anche scegliere
   solo BUY o solo SELL (con solo BUY e zona 100% compra sempre).
2. **Martingala**: ogni volta che il prezzo va contro l'ultimo ordine della
   distanza della fase (30 o 150 pips), l'EA apre un nuovo ordine nella stessa
   direzione con il lotto moltiplicato (0.01 → 0.02 → 0.04 → …).
3. **Stop loss**: spento di default. Se lo attivi, ogni ordine ha il suo
   stop loss (quello della fase in cui è stato aperto).
4. **Chiusura in profitto**: quando il ciclo, contando anche le perdite già
   chiuse in stop loss, guadagna l'obiettivo (40 pips sul lotto iniziale),
   l'EA chiude tutto e riparte dal lotto iniziale.
5. **Fine in perdita** (solo con lo stop loss attivo): se tutte le posizioni
   sono chiuse in stop loss e sono già stati usati gli 8 ordini, oppure si è
   in fase 3, il ciclo finisce e l'EA riparte dal lotto iniziale. Senza stop
   loss le posizioni restano aperte finché il ciclo non arriva all'obiettivo.

### Parametri principali

| Parametro | Default | Cosa fa |
|---|---|---|
| Timeframe / candele del range | H1 / 24 | Su cosa si misura l'ampiezza del mercato |
| FASE 1 se range ≤ | 300 pips | Soglia del mercato laterale |
| FASE 2 se range < | 2000 pips | Oltre questa soglia l'EA non apre più ordini |
| Distanza fase 1 | 30 pips | |
| Stop loss fase 1 | 0 (spento) | Es. 500 point per riattivarlo |
| Distanza fase 2 | 150 pips | Distanza minima |
| Divisore fase 2 | 8 | Distanza = range ÷ 8 se è più grande di 150 pips (0 = sempre 150) |
| Stop loss fase 2 | 0 (spento) | Es. 650 point per riattivarlo |
| Lotto iniziale | 0.01 | |
| Moltiplicatore | 2.0 | |
| Lotto massimo | 1.00 | Limite per singolo ordine |
| Ordini massimi per ciclo | 8 | |
| Obiettivo ciclo | 40 pips | Calcolato sul lotto iniziale (XAUUSD 0.01 → circa 4 $) |
| Perdita massima ciclo | 0 (off) | Chiude tutto se il ciclo perde questa % del saldo |
| Timeframe / candele di ingresso | M5 / 12 | Range breve per decidere BUY o SELL |
| Zona di ingresso | 50% | 50 = entra sempre; più basso = più selettivo |
| Point in 1 pip | 0 (automatico) | 10 su forex a 5 cifre e sull'oro (1 pip = 0.10) |

Ricorda: **1 pip = 10 point** sul forex a 5 cifre e sull'oro.
Quindi 30 pips = 300 point, 150 pips = 1500 point.

### Installazione e prova

1. In MetaTrader 5: *File → Apri cartella dati* → `MQL5/Experts/`, copia lì
   `MartingalaTreFasi.mq5`.
2. Aprilo con MetaEditor e premi **F7** (Compila).
3. Prova nel **Tester strategie** (Ctrl+R): scegli l'EA, il simbolo
   (es. XAUUSD), modalità *Ogni tick basato su tick reali*.
4. Serve un conto **hedging**: su un conto netting l'EA non parte.

Il pannello sul grafico mostra fase, range, distanza attuale, ordini del
ciclo, prossimo lotto e risultato del ciclo rispetto all'obiettivo.

### Da sapere

- Senza stop loss non c'è nessun limite alla perdita: se il prezzo continua
  ad andare contro dopo l'ottavo ordine, le posizioni restano aperte e la
  perdita cresce. Nel tester guarda il **drawdown massimo** nel report.
- Se riattivi lo stop loss in fase 2 con 650 point (65 pips), è più corto
  della distanza tra gli ordini (150 pips): ogni ordine viene chiuso prima che
  si apra il successivo. Per avere più ordini aperti insieme in fase 2 serve
  uno stop loss sopra i 1500 point.
- La martingala può perdere molto in fretta: con moltiplicatore 2 e 8 ordini
  l'ultimo lotto è 128 volte il primo (limitato a 1.00 dal lotto massimo).
  Provala prima nel tester o su un conto demo.

---

## Scalper Rapido Direzionale (Expert Advisor MetaTrader 5)

File: [`MQL5/Experts/ScalperBilaterale.mq5`](MQL5/Experts/ScalperBilaterale.mq5)

Opera su **entrambi i lati, uno alla volta**: ogni operazione è solo BUY o
solo SELL, mai mescolate. Finita un'operazione ne comincia subito un'altra.

### Come funziona

1. **Attesa**: tiene un **BUY STOP** sopra e un **SELL STOP** sotto il prezzo,
   a 1 pip (o di più se la volatilità sale). I pendenti seguono il prezzo:
   si avvicinano quando si allontana, così si entra appena il prezzo si muove.
   Ogni pendente ha già lo stop attaccato.
2. **Direzione**: il primo pendente preso decide l'operazione (BUY o SELL);
   l'altro viene cancellato subito. Se per un movimento violentissimo entrano
   tutti e due, il lato entrato per secondo viene chiuso.
3. **Massimo 3 ordini a 2 pips**: se il prezzo continua nella direzione
   giusta entra il 2° ordine a +2 pips e il 3° a +4 pips.
4. **Trailing stop dinamico**: uno stop unico per tutti gli ordini, a 2 pips
   dal prezzo (o ATR M1 × 0.5 se più grande), che si muove solo a favore.
   Quando viene preso l'operazione è finita e ripartono i pendenti BUY e SELL.
5. **Size che cresce con il saldo**: 0.01 lotti ogni 1000 di saldo
   (saldo 5000 → 0.05 per ordine), mai sotto 0.01 e mai sopra 1 lotto.

### Parametri principali

| Parametro | Default | Cosa fa |
|---|---|---|
| Lati | BUY e SELL | Oppure solo BUY / solo SELL |
| Distanza pendenti di ingresso | 1 pip | Distanza minima dal prezzo |
| Fattore ATR ingresso | 0.3 | Distanza = ATR M1 × fattore se più grande (0 = fissa) |
| Spread massimo | 50 point | Sopra questo spread non entra (0 = nessun filtro) |
| Ordini massimi | 3 | Per operazione |
| Passo | 2 pips | Distanza tra gli ordini |
| Lotto minimo | 0.01 | |
| Lotti ogni 1000 di saldo | 0.01 | 0 = sempre il lotto minimo |
| Lotto massimo | 1.00 | Per ordine |
| Trailing | 2 pips | Distanza minima dello stop dal prezzo |
| Fattore ATR trailing | 0.5 | Stop = ATR M1 × fattore se più grande (0 = fisso) |
| Passo trailing | 0.2 pips | Lo stop si sposta quando può avanzare almeno di tanto |

### Da sapere

- Serve un conto **hedging** (su netting i 3 ordini diventerebbero una sola
  posizione).
- Lo spread conta molto: con stop a 2 pips e spread di 2–3 pips ogni
  operazione parte già in perdita. Provalo su un broker a spread basso.
- Nel tester usa **Ogni tick basato su tick reali**: con i tick generati uno
  scalper da 1–2 pips dà risultati falsi.
