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

## Scalper Trend (Expert Advisor MetaTrader 5)

File: [`MQL5/Experts/ScalperTrend.mq5`](MQL5/Experts/ScalperTrend.mq5)

Scalper dinamico a favore di trend: decide a ogni candela M1 (ogni minuto),
entra solo se il prezzo conferma entro 1 minuto e calcola la size dal rischio
in % del saldo. Prende il posto del vecchio *Scalper Bilaterale*, che entrava
a ogni movimento di 1 pip con stop a 2 pips e veniva mangiato dallo spread.

### Come funziona

1. **Trend (M5)**: EMA 50 sopra EMA 200, EMA 50 che sale e prezzo sopra la
   EMA 50 → solo BUY. Il contrario → solo SELL. Con ADX sotto 20 (trend
   debole) non entra. BUY e SELL mai insieme.
2. **Ingresso (M1)**, a ogni candela chiusa, in due modi:
   - **pullback**: il prezzo torna a toccare la EMA 20 e la candela chiude di
     nuovo a favore del trend;
   - **rottura**: candela decisa (corpo almeno metà della candela) che chiude
     oltre il massimo/minimo delle ultime 5 candele.

   Il bot mette un **BUY STOP sopra il massimo** (o **SELL STOP sotto il
   minimo**) della candela: entra solo se il prezzo conferma **entro 1
   minuto**, altrimenti annulla.
3. **Precisione**: niente ingresso dopo candele enormi (oltre 2.5 × ATR,
   spike da notizie). Lo stop è sempre almeno 4 volte lo spread, così lo
   spread non mangia l'operazione.
4. **Stop loss** = ATR M1 × 1.5 (minimo 5 pips), già attaccato al pendente.
5. **Pareggio e trailing**: a +1R lo stop va in pari (+1 pip); da lì segue il
   prezzo a ATR × 1, solo a favore.
6. **Fino a 3 ordini a 2 pips**: aggiunti solo quando lo stop è già abbastanza
   avanti da garantire che tutta l'operazione non chiuda in perdita.
7. **Size che cresce con il capitale**: ogni operazione rischia lo 0.5% del
   saldo (saldo 1000 → 5; saldo 5000 → 25). Lotto arrotondato per difetto,
   quindi non supera mai il rischio. Se il capitale è troppo piccolo per lo
   stop, salta il segnale.

### Parametri principali

| Parametro | Default | Cosa fa |
|---|---|---|
| Timeframe trend / EMA | M5 / 50 e 200 | Direzione del trend |
| ADX minimo | 20 | Forza minima del trend (0 = nessun filtro) |
| Timeframe ingresso | M1 | Decide a ogni candela |
| Ingresso pullback / rottura | sì / sì | Si possono spegnere uno alla volta |
| EMA pullback | 20 | |
| Candele rottura | 5 | |
| Candela massima | ATR × 2.5 | Oltre = spike, niente ingresso |
| Validità segnale | 1 candela (1 minuto) | |
| Stop loss | ATR × 1.5, minimo 5 pips e 4 × spread | |
| Pareggio | a +1R, con +1 pip | |
| Trailing | ATR × 1 | 0 = solo pareggio |
| Take profit | 0 (spento) | Stop × R se lo vuoi fisso |
| Rischio per operazione | 0.5% del saldo | La size cresce con il capitale |
| Ordini massimi / passo | 3 / 2 pips | |
| Stop giornaliero | 0 (spento) | Es. 3 = stop per oggi a -3% |
| Operazioni massime al giorno | 0 (nessun limite) | |
| Spread massimo | 40 point | |
| Orario | 9–21 (ora del server) | Inizio = fine → sempre |

### Da sapere

- Nessun bot garantisce profitto. Il drawdown si tiene basso soprattutto con
  il **rischio per operazione**: con 0.5%, 10 perdite di fila fanno circa -5%.
- Entra più spesso della versione su M5. Per ancora più ingressi: abbassa
  l'ADX minimo (es. 15) o allarga l'orario. Per meno ingressi ma più
  selezionati: spegni la rottura e tieni solo il pullback.
- Provalo prima nel **tester** con **Ogni tick basato su tick reali** su
  qualche mese, poi su un **conto demo**, e solo dopo con soldi veri.
