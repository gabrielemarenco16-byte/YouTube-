# YouTube-

## Martingala 3 Fasi (Expert Advisor MetaTrader 5)

File: [`MQL5/Experts/MartingalaTreFasi.mq5`](MQL5/Experts/MartingalaTreFasi.mq5)

Martingala a griglia che cambia la distanza tra gli ordini e lo stop loss
in base a quanto è ampio il mercato (il range tra il massimo e il minimo
delle ultime candele, di default le ultime 24 candele H1).

### Le tre fasi

| Fase | Quando | Distanza tra gli ordini | Stop loss di ogni ordine |
|---|---|---|---|
| **1 – Laterale** | range ≤ 300 pips | 30 pips | 500 point |
| **2 – Allargamento** | range tra 300 e 2000 pips | 150 pips, oppure range ÷ 8 se è più grande (a 2000 pips sono 250 pips) | 650 point |
| **3 – Stop** | range ≥ 2000 pips | nessun nuovo ordine | – |

Durante un ciclo il range conta tutto quello che è successo dall'inizio del
ciclo, quindi la fase può solo salire (1 → 2 → 3). Così l'EA non torna alla
griglia stretta da 30 pips mentre è già in perdita.

### Come funziona un ciclo

1. **Ingresso**: compra se il prezzo è nel 30% basso del range, vende se è nel
   30% alto (in un mercato laterale il prezzo tende a tornare indietro).
   Puoi anche scegliere solo BUY o solo SELL.
2. **Martingala**: ogni volta che il prezzo va contro l'ultimo ordine della
   distanza della fase (30 o 150 pips), l'EA apre un nuovo ordine nella stessa
   direzione con il lotto moltiplicato (0.01 → 0.02 → 0.04 → …).
3. **Stop loss**: ogni ordine ha il suo stop loss (500 point in fase 1,
   650 point in fase 2).
4. **Chiusura in profitto**: quando il ciclo, contando anche le perdite già
   chiuse in stop loss, guadagna l'obiettivo (40 pips sul lotto iniziale),
   l'EA chiude tutto e riparte dal lotto iniziale.
5. **Fine in perdita**: se tutte le posizioni sono chiuse in stop loss e sono
   già stati usati gli 8 ordini, oppure si è in fase 3, il ciclo finisce e
   l'EA riparte dal lotto iniziale.

### Parametri principali

| Parametro | Default | Cosa fa |
|---|---|---|
| Timeframe / candele del range | H1 / 24 | Su cosa si misura l'ampiezza del mercato |
| FASE 1 se range ≤ | 300 pips | Soglia del mercato laterale |
| FASE 2 se range < | 2000 pips | Oltre questa soglia l'EA non apre più ordini |
| Distanza fase 1 | 30 pips | |
| Stop loss fase 1 | 500 point | |
| Distanza fase 2 | 150 pips | Distanza minima |
| Divisore fase 2 | 8 | Distanza = range ÷ 8 se è più grande di 150 pips (0 = sempre 150) |
| Stop loss fase 2 | 650 point | |
| Lotto iniziale | 0.01 | |
| Moltiplicatore | 2.0 | |
| Lotto massimo | 1.00 | Limite per singolo ordine |
| Ordini massimi per ciclo | 8 | |
| Obiettivo ciclo | 40 pips | Calcolato sul lotto iniziale (XAUUSD 0.01 → circa 4 $) |
| Perdita massima ciclo | 0 (off) | Chiude tutto se il ciclo perde questa % del saldo |
| Zona di ingresso | 30% | |
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

- In fase 2 lo stop loss (650 point = 65 pips) è più corto della distanza tra
  gli ordini (150 pips): ogni ordine viene chiuso in stop loss prima che si
  apra il successivo. Quindi in fase 2 non c'è una vera griglia: l'ordine dopo,
  con il lotto raddoppiato, si apre 150 pips più in là. Se vuoi più ordini
  aperti insieme, porta lo stop loss della fase 2 sopra i 1500 point.
- La martingala può perdere molto in fretta: con moltiplicatore 2 e 8 ordini
  l'ultimo lotto è 128 volte il primo (limitato a 1.00 dal lotto massimo).
  Provala prima nel tester o su un conto demo.
