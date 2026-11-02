//+------------------------------------------------------------------+
//|                                                 ScalperTrend.mq5 |
//|  Scalper a favore di trend: entrate selezionate e confermate,    |
//|  size a rischio fisso che cresce con il capitale                 |
//|                                                                  |
//|  - Trend (M15): EMA 50 sopra/sotto EMA 200, EMA 50 inclinata e   |
//|    ADX >= 20. Solo BUY in trend rialzista, solo SELL in ribasso, |
//|    mai insieme. Senza trend non entra.                           |
//|  - Ingresso (M5): pullback che tocca l'EMA 20, poi una candela   |
//|    che chiude di nuovo a favore del trend. Pendente STOP sopra   |
//|    il massimo (BUY) o sotto il minimo (SELL) di quella candela:  |
//|    entra solo se il prezzo conferma. Valido 2 candele.           |
//|  - Stop = ATR x 1.5 (minimo 10 pips), pareggio a +1R, poi        |
//|    trailing stop a ATR x 1 che segue il prezzo.                  |
//|  - Size: rischio 1% del saldo per operazione, quindi cresce (e   |
//|    cala) con il capitale. Se il capitale e' troppo piccolo per   |
//|    lo stop, non entra invece di rischiare troppo.                |
//|  - Fino a 3 ordini a 2 pips, aggiunti solo quando lo stop        |
//|    garantisce che l'operazione non chiude in perdita.            |
//|  - Filtri: orari e spread massimo. Stop giornaliero e limite di  |
//|    operazioni al giorno ci sono ma sono spenti di default.       |
//+------------------------------------------------------------------+
#property copyright   "gabrielemarenco16-byte"
#property version     "3.00"
#property description "Scalper a favore di trend: pullback + rottura confermata, stop ATR, pareggio e trailing,"
#property description "size a rischio % del saldo (cresce con il capitale)."

#include <Trade\Trade.mqh>

enum ENUM_LATI
  {
   LATI_ENTRAMBI  = 0, // BUY e SELL (uno alla volta, secondo il trend)
   LATI_SOLO_BUY  = 1, // Solo BUY
   LATI_SOLO_SELL = 2  // Solo SELL
  };

//+------------------------------------------------------------------+
//| Parametri                                                        |
//+------------------------------------------------------------------+
input group "=== Trend (filtro principale) ==="
input ENUM_TIMEFRAMES InpTFTrend    = PERIOD_M15; // Timeframe del trend
input int             InpEMAVeloce  = 50;         // EMA veloce
input int             InpEMALenta   = 200;        // EMA lenta
input int             InpADXPeriodo = 14;         // Periodo ADX
input double          InpADXMin     = 20;         // Forza minima del trend (ADX, 0 = nessun filtro)

input group "=== Ingresso preciso (pullback + rottura) ==="
input ENUM_TIMEFRAMES InpTFIngresso    = PERIOD_M5;     // Timeframe di ingresso
input int             InpEMAPullback   = 20;            // EMA del pullback
input int             InpBarrePullback = 3;             // Il pullback deve toccare l'EMA nelle ultime N candele
input double          InpBufferPips    = 0.5;           // Margine oltre il massimo/minimo della candela (pips)
input int             InpValiditaBarre = 2;             // Il segnale resta valido per N candele
input ENUM_LATI       InpLati          = LATI_ENTRAMBI; // Lati su cui operare

input group "=== Stop loss, pareggio, trailing ==="
input int    InpATRPeriodo  = 14;  // Periodo ATR (timeframe di ingresso)
input double InpSLATR       = 1.5; // Stop loss = ATR x
input double InpSLMinPips   = 10;  // Stop loss minimo (pips)
input double InpBreakEvenR  = 1.0; // Stop in pari quando il profitto arriva a R x lo stop
input double InpBELockPips  = 1.0; // Pips garantiti con il pareggio
input double InpTrailingATR = 1.0; // Dopo il pareggio lo stop segue il prezzo a ATR x (0 = solo pareggio)
input double InpTPR         = 0;   // Take profit = stop x R (0 = nessuno, solo trailing)

input group "=== Size: cresce con il capitale ==="
input double InpRischioPerc  = 1.0; // Rischio per operazione (% del saldo)
input int    InpMaxOrdini    = 3;   // Ordini massimi per operazione
input double InpPassoPips    = 2.0; // Distanza minima tra gli ordini aggiunti (pips)
input double InpLottoMassimo = 5.0; // Lotto massimo per ordine (0 = nessun limite)

input group "=== Protezione del capitale ==="
input double InpPerditaMaxGiornoPerc = 0;   // Stop per oggi se il giorno perde questa % del saldo (0 = off, es. 3)
input int    InpMaxOperazioniGiorno  = 0;   // Operazioni massime al giorno (0 = nessun limite, es. 10)
input int    InpSpreadMaxPoint       = 40;  // Spread massimo per entrare (point, 0 = nessun filtro)
input int    InpOraInizio            = 9;   // Ora di inizio (ora del server, uguale a fine = sempre)
input int    InpOraFine              = 21;  // Ora di fine (ora del server)

input group "=== Varie ==="
input long   InpMagic         = 20261104; // Magic number
input int    InpSlippagePoint = 10;       // Slippage massimo (point)
input double InpPointPerPip   = 0;        // Point in 1 pip (0 = automatico: 10 su forex 5 cifre e oro)
input bool   InpPannello      = true;     // Mostra il pannello sul grafico

//+------------------------------------------------------------------+
//| Stato                                                            |
//+------------------------------------------------------------------+
CTrade   g_trade;
double   g_pipPoints     = 10;
double   g_pip           = 0;
int      g_maxOrdini     = 3;
int      g_hEmaVeloce    = INVALID_HANDLE;
int      g_hEmaLenta     = INVALID_HANDLE;
int      g_hADX          = INVALID_HANDLE;
int      g_hEmaPullback  = INVALID_HANDLE;
int      g_hATR          = INVALID_HANDLE;
datetime g_pausaFinoA    = 0;
datetime g_ultimaCandela = 0;
int      g_trend         = 0;   // +1 rialzo, -1 ribasso, 0 nessun trend

//--- segnale in attesa
int      g_segnale  = 0;        // +1 BUY, -1 SELL, 0 nessuno
double   g_livello  = 0;
double   g_sl       = 0;
double   g_tp       = 0;
datetime g_scadenza = 0;

//--- giornata
bool     g_aggiornaStorico = true;
datetime g_giornoCache     = 0;
double   g_pnlOggi         = 0;
int      g_operazioniOggi  = 0;

//--- operazione aperta (riletta a ogni tick)
int    g_nBuy           = 0;
int    g_nSell          = 0;
double g_primoPrezzo    = 0;    // ingresso del primo ordine
double g_primoVolume    = 0;
double g_ultimoPrezzo   = 0;    // ingresso piu' avanzato nella direzione
double g_stop           = 0;    // stop comune (il piu' stretto)
double g_tpAttuale      = 0;
double g_profitto       = 0;
double g_sommaVol       = 0;
double g_sommaVolPrezzo = 0;

//+------------------------------------------------------------------+
int OnInit()
  {
   if(InpEMAVeloce < 1 || InpEMALenta <= InpEMAVeloce || InpADXPeriodo < 1 || InpADXMin < 0 ||
      InpEMAPullback < 1 || InpBarrePullback < 1 || InpBufferPips < 0 || InpValiditaBarre < 1 ||
      InpATRPeriodo < 1 || InpSLATR <= 0 || InpSLMinPips < 0 || InpBreakEvenR <= 0 || InpBELockPips < 0 ||
      InpTrailingATR < 0 || InpTPR < 0 || InpRischioPerc <= 0 || InpRischioPerc > 10 ||
      InpMaxOrdini < 1 || InpPassoPips <= 0 || InpPerditaMaxGiornoPerc < 0 ||
      InpOraInizio < 0 || InpOraInizio > 24 || InpOraFine < 0 || InpOraFine > 24)
     {
      Print("Parametri non validi: controlla gli input (rischio tra 0 e 10%, EMA lenta > EMA veloce).");
      return INIT_PARAMETERS_INCORRECT;
     }

   g_maxOrdini = InpMaxOrdini;
   if((ENUM_ACCOUNT_MARGIN_MODE)AccountInfoInteger(ACCOUNT_MARGIN_MODE) != ACCOUNT_MARGIN_MODE_RETAIL_HEDGING &&
      g_maxOrdini > 1)
     {
      Print("Conto netting: gli ordini si fonderebbero in una sola posizione, uso 1 ordine per operazione.");
      g_maxOrdini = 1;
     }

   g_pipPoints = CalcolaPointPerPip();
   g_pip       = g_pipPoints * _Point;

   g_trade.SetExpertMagicNumber((ulong)InpMagic);
   g_trade.SetDeviationInPoints((ulong)InpSlippagePoint);
   g_trade.SetTypeFillingBySymbol(_Symbol);

   g_hEmaVeloce   = iMA(_Symbol, InpTFTrend, InpEMAVeloce, 0, MODE_EMA, PRICE_CLOSE);
   g_hEmaLenta    = iMA(_Symbol, InpTFTrend, InpEMALenta, 0, MODE_EMA, PRICE_CLOSE);
   g_hADX         = iADX(_Symbol, InpTFTrend, InpADXPeriodo);
   g_hEmaPullback = iMA(_Symbol, InpTFIngresso, InpEMAPullback, 0, MODE_EMA, PRICE_CLOSE);
   g_hATR         = iATR(_Symbol, InpTFIngresso, InpATRPeriodo);
   if(g_hEmaVeloce == INVALID_HANDLE || g_hEmaLenta == INVALID_HANDLE || g_hADX == INVALID_HANDLE ||
      g_hEmaPullback == INVALID_HANDLE || g_hATR == INVALID_HANDLE)
     {
      Print("Impossibile creare gli indicatori.");
      return INIT_FAILED;
     }

   PrintFormat("Scalper trend su %s: 1 pip = %.0f point, rischio %.1f%% per operazione, max %d ordini",
               _Symbol, g_pipPoints, InpRischioPerc, g_maxOrdini);
   return INIT_SUCCEEDED;
  }

//+------------------------------------------------------------------+
void OnDeinit(const int reason)
  {
   if(reason == REASON_REMOVE)
      CancellaPendenti(); // bot tolto dal grafico: nessun pendente lasciato senza gestione
   IndicatorRelease(g_hEmaVeloce);
   IndicatorRelease(g_hEmaLenta);
   IndicatorRelease(g_hADX);
   IndicatorRelease(g_hEmaPullback);
   IndicatorRelease(g_hATR);
   Comment("");
  }

//+------------------------------------------------------------------+
void OnTrade()
  {
   g_aggiornaStorico = true;
  }

//+------------------------------------------------------------------+
void OnTick()
  {
   LeggiPosizioni();
   LeggiStoricoOggi();
   int nPos = g_nBuy + g_nSell;

   //--- stop giornaliero: chiudo tutto e non entro piu' fino a domani
   if(StopGiornaliero())
     {
      CancellaPendenti();
      g_segnale = 0;
      if(nPos > 0)
         ChiudiTutto("perdita massima del giorno");
      Pannello(true);
      return;
     }

   //--- nuova candela di ingresso: aggiorno il trend e cerco un segnale
   datetime candela = iTime(_Symbol, InpTFIngresso, 0);
   if(candela > 0 && candela != g_ultimaCandela)
     {
      g_ultimaCandela = candela;
      g_trend = Trend();
      if(nPos == 0)
         CercaSegnale();
     }

   if(nPos > 0)
     {
      g_segnale = 0;
      CancellaPendenti();
      ENUM_POSITION_TYPE dir = (g_nBuy > 0) ? POSITION_TYPE_BUY : POSITION_TYPE_SELL;
      GestisciStop(dir);
      GestisciAggiunte(dir);
     }
   else
      GestisciPendente();

   Pannello(false);
  }

//+------------------------------------------------------------------+
//| Trend sul timeframe superiore                                    |
//+------------------------------------------------------------------+
int Trend()
  {
   double veloce1  = ValoreBuffer(g_hEmaVeloce, 0, 1);
   double veloce3  = ValoreBuffer(g_hEmaVeloce, 0, 3);
   double lenta1   = ValoreBuffer(g_hEmaLenta, 0, 1);
   double chiusura = iClose(_Symbol, InpTFTrend, 1);
   if(veloce1 <= 0 || veloce3 <= 0 || lenta1 <= 0 || chiusura <= 0)
      return 0;

   if(InpADXMin > 0 && ValoreBuffer(g_hADX, 0, 1) < InpADXMin)
      return 0; // trend troppo debole: meglio non entrare

   if(veloce1 > lenta1 && veloce1 > veloce3 && chiusura > veloce1)
      return 1;
   if(veloce1 < lenta1 && veloce1 < veloce3 && chiusura < veloce1)
      return -1;
   return 0;
  }

//+------------------------------------------------------------------+
//| Pullback sull'EMA e candela di ripartenza a favore del trend     |
//+------------------------------------------------------------------+
bool SegnalePullback(bool buy)
  {
   double apertura = iOpen(_Symbol, InpTFIngresso, 1);
   double chiusura = iClose(_Symbol, InpTFIngresso, 1);
   double ema1     = ValoreBuffer(g_hEmaPullback, 0, 1);
   if(apertura <= 0 || chiusura <= 0 || ema1 <= 0)
      return false;

   //--- la candela segnale chiude a favore del trend e dalla parte giusta dell'EMA
   if(buy && !(chiusura > apertura && chiusura > ema1))
      return false;
   if(!buy && !(chiusura < apertura && chiusura < ema1))
      return false;

   //--- nelle ultime candele il prezzo e' tornato a toccare l'EMA (pullback)
   for(int k = 1; k <= InpBarrePullback; k++)
     {
      double ema = ValoreBuffer(g_hEmaPullback, 0, k);
      if(ema <= 0)
         return false;
      if(buy && iLow(_Symbol, InpTFIngresso, k) <= ema)
         return true;
      if(!buy && iHigh(_Symbol, InpTFIngresso, k) >= ema)
         return true;
     }
   return false;
  }

//+------------------------------------------------------------------+
//| A ogni nuova candela: se c'e' un segnale ne salvo livello e stop |
//+------------------------------------------------------------------+
void CercaSegnale()
  {
   int segnale = 0;
   if(g_trend > 0 && InpLati != LATI_SOLO_SELL && SegnalePullback(true))
      segnale = 1;
   if(g_trend < 0 && InpLati != LATI_SOLO_BUY && SegnalePullback(false))
      segnale = -1;

   if(segnale == 0)
     {
      if(g_segnale != 0 && g_segnale != g_trend) // il trend non c'e' piu': il segnale decade
        {
         g_segnale = 0;
         CancellaPendenti();
        }
      return;
     }

   MqlTick t;
   double  atr = ValoreBuffer(g_hATR, 0, 1);
   if(!SymbolInfoTick(_Symbol, t) || atr <= 0)
      return;

   double slDist = MathMax(atr * InpSLATR, InpSLMinPips * g_pip);
   double buffer = InpBufferPips * g_pip;
   double spread = t.ask - t.bid;

   if(segnale > 0)
     {
      g_livello = ArrotondaTick(iHigh(_Symbol, InpTFIngresso, 1) + spread + buffer, true);
      g_sl      = ArrotondaTick(g_livello - slDist, false);
      g_tp      = (InpTPR > 0) ? ArrotondaTick(g_livello + slDist * InpTPR, true) : 0;
     }
   else
     {
      g_livello = ArrotondaTick(iLow(_Symbol, InpTFIngresso, 1) - buffer, false);
      g_sl      = ArrotondaTick(g_livello + slDist, true);
      g_tp      = (InpTPR > 0) ? ArrotondaTick(g_livello - slDist * InpTPR, false) : 0;
     }
   g_segnale  = segnale;
   g_scadenza = TimeCurrent() + InpValiditaBarre * PeriodSeconds(InpTFIngresso);
   CancellaPendenti(); // un eventuale pendente vecchio viene sostituito

   PrintFormat("Segnale %s: ingresso a %s, stop %s (%.1f pips)", segnale > 0 ? "BUY" : "SELL",
               DoubleToString(g_livello, _Digits), DoubleToString(g_sl, _Digits), slDist / g_pip);
  }

//+------------------------------------------------------------------+
//| Nessuna operazione aperta: piazza o toglie il pendente del       |
//| segnale                                                          |
//+------------------------------------------------------------------+
void GestisciPendente()
  {
   bool esiste = ContaPendenti() > 0;

   if(g_segnale == 0 || TimeCurrent() >= g_scadenza)
     {
      if(g_segnale != 0)
         Print("Segnale scaduto senza conferma: nessun ingresso.");
      g_segnale = 0;
      if(esiste)
         CancellaPendenti();
      return;
     }

   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t) || t.ask <= 0 || t.bid <= 0)
      return;

   //--- condizioni non buone: tolgo il pendente, il segnale resta valido fino alla scadenza
   bool condizioni = SpreadOk(t) && OrarioOk() &&
                     (InpMaxOperazioniGiorno <= 0 || g_operazioniOggi < InpMaxOperazioniGiorno);
   if(!condizioni)
     {
      if(esiste)
         CancellaPendenti();
      return;
     }
   if(esiste || TimeCurrent() < g_pausaFinoA)
      return;

   double minimo = DistanzaMinimaBroker();
   if(g_segnale > 0)
     {
      if(t.ask > g_livello)
        {
         Print("Rottura gia' avvenuta senza pendente: segnale annullato.");
         g_segnale = 0;
         return;
        }
      if(g_livello - t.ask < minimo)
         return; // troppo vicino per il broker: aspetto
     }
   else
     {
      if(t.bid < g_livello)
        {
         Print("Rottura gia' avvenuta senza pendente: segnale annullato.");
         g_segnale = 0;
         return;
        }
      if(t.bid - g_livello < minimo)
         return;
     }

   double lotto = LottoRischio(MathAbs(g_livello - g_sl));
   if(lotto <= 0)
     {
      g_segnale = 0;
      return;
     }

   double          margine = 0;
   ENUM_ORDER_TYPE mercato = (g_segnale > 0) ? ORDER_TYPE_BUY : ORDER_TYPE_SELL;
   if(!OrderCalcMargin(mercato, _Symbol, lotto, g_livello, margine) || margine > AccountInfoDouble(ACCOUNT_MARGIN_FREE))
     {
      PrintFormat("Margine insufficiente per %.2f lotti: segnale annullato.", lotto);
      g_segnale = 0;
      return;
     }

   bool ok = (g_segnale > 0)
             ? g_trade.BuyStop(lotto, g_livello, _Symbol, g_sl, g_tp, ORDER_TIME_GTC, 0, "ST ingresso")
             : g_trade.SellStop(lotto, g_livello, _Symbol, g_sl, g_tp, ORDER_TIME_GTC, 0, "ST ingresso");
   if(Esito(ok, "piazzamento pendente"))
      PrintFormat("Pendente %s %.2f lotti a %s, stop %s", g_segnale > 0 ? "BUY STOP" : "SELL STOP", lotto,
                  DoubleToString(g_livello, _Digits), DoubleToString(g_sl, _Digits));
  }

//+------------------------------------------------------------------+
//| Pareggio a +1R, poi trailing stop a ATR che segue il prezzo.     |
//| Lo stop e' unico per tutti gli ordini e si muove solo a favore.  |
//+------------------------------------------------------------------+
void GestisciStop(ENUM_POSITION_TYPE dir)
  {
   if(TimeCurrent() < g_pausaFinoA)
      return;
   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t) || t.ask <= 0 || t.bid <= 0)
      return;

   bool   buy    = (dir == POSITION_TYPE_BUY);
   double prezzo = buy ? t.bid : t.ask;
   double atr    = ValoreBuffer(g_hATR, 0, 1);
   double minimo = DistanzaMinimaBroker();
   double passo  = MathMax(0.5 * g_pip, SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE));
   double nuovo  = g_stop;

   //--- ordine senza stop (non dovrebbe capitare): metto subito lo stop ATR
   if(nuovo <= 0)
     {
      double slDist = MathMax(atr * InpSLATR, InpSLMinPips * g_pip);
      nuovo = buy ? ArrotondaTick(g_primoPrezzo - slDist, false) : ArrotondaTick(g_primoPrezzo + slDist, true);
     }

   bool inPari = buy ? (nuovo >= g_primoPrezzo) : (nuovo <= g_primoPrezzo);
   if(!inPari)
     {
      double R        = MathAbs(g_primoPrezzo - nuovo);
      double guadagno = buy ? prezzo - g_primoPrezzo : g_primoPrezzo - prezzo;
      if(R > 0 && guadagno >= R * InpBreakEvenR)
         nuovo = buy ? ArrotondaTick(g_primoPrezzo + InpBELockPips * g_pip, true)
                     : ArrotondaTick(g_primoPrezzo - InpBELockPips * g_pip, false);
     }
   else
      if(atr > 0 && InpTrailingATR > 0)
        {
         double candidato = buy ? ArrotondaTick(prezzo - atr * InpTrailingATR, false)
                                : ArrotondaTick(prezzo + atr * InpTrailingATR, true);
         if(buy ? candidato > nuovo : candidato < nuovo)
            nuovo = candidato;
        }

   //--- troppo vicino al prezzo per il broker: aspetto
   if(buy ? (prezzo - nuovo < minimo) : (nuovo - prezzo < minimo))
      return;

   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0 || !PosizioneDelBot() || (ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE) != dir)
         continue;
      double sl = PositionGetDouble(POSITION_SL);
      double tp = PositionGetDouble(POSITION_TP);
      bool migliora = (sl <= 0) || (buy ? nuovo - sl >= passo : sl - nuovo >= passo);
      if(!migliora)
         continue;
      if(!Esito(g_trade.PositionModify(ticket, nuovo, tp), "spostamento stop"))
         return;
     }
  }

//+------------------------------------------------------------------+
//| Ordini aggiunti (max 3 in tutto, a 2 pips): solo se, con lo stop |
//| attuale, l'operazione intera non puo' chiudere in perdita        |
//+------------------------------------------------------------------+
void GestisciAggiunte(ENUM_POSITION_TYPE dir)
  {
   if(g_nBuy + g_nSell >= g_maxOrdini || g_stop <= 0 || TimeCurrent() < g_pausaFinoA)
      return;
   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t) || t.ask <= 0 || t.bid <= 0)
      return;
   if(!SpreadOk(t) || !OrarioOk())
      return;

   bool   buy    = (dir == POSITION_TYPE_BUY);
   double prezzo = buy ? t.ask : t.bid;
   double passo  = InpPassoPips * g_pip;
   if(buy ? prezzo < g_ultimoPrezzo + passo : prezzo > g_ultimoPrezzo - passo)
      return;
   if(buy ? (t.bid - g_stop < DistanzaMinimaBroker()) : (g_stop - t.ask < DistanzaMinimaBroker()))
      return;

   double valore   = ValorePrezzoPerLotto();
   double bloccato = (buy ? g_sommaVol * g_stop - g_sommaVolPrezzo : g_sommaVolPrezzo - g_sommaVol * g_stop) * valore;
   double lotto    = g_primoVolume;
   double rischio  = lotto * (buy ? prezzo - g_stop : g_stop - prezzo) * valore;
   if(valore <= 0 || bloccato < rischio)
      return; // l'aggiunta potrebbe portare l'operazione in perdita

   double margine = 0;
   if(!OrderCalcMargin(buy ? ORDER_TYPE_BUY : ORDER_TYPE_SELL, _Symbol, lotto, prezzo, margine) ||
      margine > AccountInfoDouble(ACCOUNT_MARGIN_FREE))
      return;

   bool ok = buy ? g_trade.Buy(lotto, _Symbol, prezzo, g_stop, g_tpAttuale, "ST aggiunta")
                 : g_trade.Sell(lotto, _Symbol, prezzo, g_stop, g_tpAttuale, "ST aggiunta");
   if(Esito(ok, "ordine aggiunto"))
     {
      g_pausaFinoA = TimeCurrent() + 1;
      PrintFormat("Aggiunto ordine %d/%d: %.2f lotti, stop %s", g_nBuy + g_nSell + 1, g_maxOrdini, lotto,
                  DoubleToString(g_stop, _Digits));
     }
  }

//+------------------------------------------------------------------+
//| Lotto a rischio fisso: (saldo x rischio%) / perdita allo stop.   |
//| Arrotondato per difetto per non superare mai il rischio.         |
//+------------------------------------------------------------------+
double LottoRischio(double distanzaStop)
  {
   double valore = ValorePrezzoPerLotto();
   if(distanzaStop <= 0 || valore <= 0)
      return 0;

   double rischio         = AccountInfoDouble(ACCOUNT_BALANCE) * InpRischioPerc / 100.0;
   double perditaPerLotto = distanzaStop * valore;
   double passo           = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP);
   double minimo          = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MIN);
   double massimo         = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MAX);

   double lotto = rischio / perditaPerLotto;
   if(passo > 0)
      lotto = MathFloor(lotto / passo + 1e-9) * passo;

   //--- capitale piccolo: il lotto minimo va bene solo se il rischio resta entro il doppio
   if(lotto < minimo)
     {
      if(minimo * perditaPerLotto <= 2.0 * rischio)
         lotto = minimo;
      else
        {
         PrintFormat("Capitale troppo piccolo per questo stop: con %.2f lotti rischierebbe %.2f %s (limite %.2f). Nessun ingresso.",
                     minimo, minimo * perditaPerLotto, AccountInfoString(ACCOUNT_CURRENCY), 2.0 * rischio);
         return 0;
        }
     }
   if(InpLottoMassimo > 0 && lotto > InpLottoMassimo)
      lotto = InpLottoMassimo;
   if(massimo > 0 && lotto > massimo)
      lotto = massimo;

   int cifre = 2;
   if(passo > 0)
      cifre = (int)MathMax(0.0, MathCeil(-MathLog10(passo) - 1e-9));
   return NormalizeDouble(lotto, cifre);
  }

//+------------------------------------------------------------------+
//| Valore in valuta del conto di 1.0 di prezzo con 1 lotto          |
//+------------------------------------------------------------------+
double ValorePrezzoPerLotto()
  {
   double valoreTick = SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_VALUE_LOSS);
   if(valoreTick <= 0)
      valoreTick = SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_VALUE);
   double dimTick = SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE);
   if(valoreTick <= 0 || dimTick <= 0)
      return 0;
   return valoreTick / dimTick;
  }

//+------------------------------------------------------------------+
//| Risultato del giorno (operazioni chiuse) e numero di operazioni  |
//+------------------------------------------------------------------+
void LeggiStoricoOggi()
  {
   datetime adesso = TimeCurrent();
   datetime oggi   = adesso - (adesso % 86400);
   if(!g_aggiornaStorico && oggi == g_giornoCache)
      return;
   if(!HistorySelect(oggi, adesso + 86400))
      return;

   g_pnlOggi        = 0;
   g_operazioniOggi = 0;
   for(int i = HistoryDealsTotal() - 1; i >= 0; i--)
     {
      ulong deal = HistoryDealGetTicket(i);
      if(deal == 0)
         continue;
      if(HistoryDealGetInteger(deal, DEAL_MAGIC) != InpMagic || HistoryDealGetString(deal, DEAL_SYMBOL) != _Symbol)
         continue;
      g_pnlOggi += HistoryDealGetDouble(deal, DEAL_PROFIT) + HistoryDealGetDouble(deal, DEAL_SWAP) +
                   HistoryDealGetDouble(deal, DEAL_COMMISSION) + HistoryDealGetDouble(deal, DEAL_FEE);

      //--- un'operazione comincia sempre da un pendente STOP (le aggiunte sono a mercato)
      if(HistoryDealGetInteger(deal, DEAL_ENTRY) == DEAL_ENTRY_IN)
        {
         ulong ordine = (ulong)HistoryDealGetInteger(deal, DEAL_ORDER);
         long  tipo   = HistoryOrderGetInteger(ordine, ORDER_TYPE);
         if(tipo == ORDER_TYPE_BUY_STOP || tipo == ORDER_TYPE_SELL_STOP)
            g_operazioniOggi++;
        }
     }
   g_giornoCache     = oggi;
   g_aggiornaStorico = false;
  }

//+------------------------------------------------------------------+
bool StopGiornaliero()
  {
   if(InpPerditaMaxGiornoPerc <= 0)
      return false;
   double saldoInizio = AccountInfoDouble(ACCOUNT_BALANCE) - g_pnlOggi;
   return (g_pnlOggi + g_profitto) <= -saldoInizio * InpPerditaMaxGiornoPerc / 100.0;
  }

//+------------------------------------------------------------------+
//| Lettura dell'operazione aperta                                   |
//+------------------------------------------------------------------+
void LeggiPosizioni()
  {
   g_nBuy           = 0;
   g_nSell          = 0;
   g_primoPrezzo    = 0;
   g_primoVolume    = 0;
   g_ultimoPrezzo   = 0;
   g_stop           = 0;
   g_tpAttuale      = 0;
   g_profitto       = 0;
   g_sommaVol       = 0;
   g_sommaVolPrezzo = 0;
   long tPrimo      = LONG_MAX;

   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0 || !PosizioneDelBot())
         continue;

      double prezzo = PositionGetDouble(POSITION_PRICE_OPEN);
      double volume = PositionGetDouble(POSITION_VOLUME);
      double sl     = PositionGetDouble(POSITION_SL);
      bool   buy    = (PositionGetInteger(POSITION_TYPE) == POSITION_TYPE_BUY);
      long   tms    = PositionGetInteger(POSITION_TIME_MSC);

      g_profitto       += PositionGetDouble(POSITION_PROFIT) + PositionGetDouble(POSITION_SWAP);
      g_sommaVol       += volume;
      g_sommaVolPrezzo += volume * prezzo;

      if(tms < tPrimo)
        {
         tPrimo        = tms;
         g_primoPrezzo = prezzo;
         g_primoVolume = volume;
         g_tpAttuale   = PositionGetDouble(POSITION_TP);
        }

      if(buy)
        {
         g_nBuy++;
         if(prezzo > g_ultimoPrezzo)
            g_ultimoPrezzo = prezzo;
         if(sl > g_stop)
            g_stop = sl;
        }
      else
        {
         g_nSell++;
         if(g_ultimoPrezzo == 0 || prezzo < g_ultimoPrezzo)
            g_ultimoPrezzo = prezzo;
         if(sl > 0 && (g_stop == 0 || sl < g_stop))
            g_stop = sl;
        }
     }
  }

//+------------------------------------------------------------------+
//| La posizione selezionata e' di questo bot su questo simbolo?     |
//+------------------------------------------------------------------+
bool PosizioneDelBot()
  {
   return PositionGetInteger(POSITION_MAGIC) == InpMagic && PositionGetString(POSITION_SYMBOL) == _Symbol;
  }

//+------------------------------------------------------------------+
int ContaPendenti()
  {
   int n = 0;
   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      ulong tk = OrderGetTicket(i);
      if(tk == 0)
         continue;
      if(OrderGetInteger(ORDER_MAGIC) == InpMagic && OrderGetString(ORDER_SYMBOL) == _Symbol)
         n++;
     }
   return n;
  }

//+------------------------------------------------------------------+
void CancellaPendenti()
  {
   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      ulong tk = OrderGetTicket(i);
      if(tk == 0)
         continue;
      if(OrderGetInteger(ORDER_MAGIC) != InpMagic || OrderGetString(ORDER_SYMBOL) != _Symbol)
         continue;
      g_trade.OrderDelete(tk);
     }
  }

//+------------------------------------------------------------------+
void ChiudiTutto(const string motivo)
  {
   CancellaPendenti();
   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0 || !PosizioneDelBot())
         continue;
      Esito(g_trade.PositionClose(ticket), "chiusura posizione");
     }
   PrintFormat("Chiuso tutto: %s", motivo);
  }

//+------------------------------------------------------------------+
//| Controlla l'esito; in caso di errore pausa di 1 secondo          |
//+------------------------------------------------------------------+
bool Esito(bool ok, const string operazione)
  {
   uint rc = g_trade.ResultRetcode();
   if(ok && (rc == TRADE_RETCODE_DONE || rc == TRADE_RETCODE_PLACED || rc == TRADE_RETCODE_DONE_PARTIAL))
      return true;
   PrintFormat("Errore %s: %u %s", operazione, rc, g_trade.ResultRetcodeDescription());
   g_pausaFinoA = TimeCurrent() + 1;
   return false;
  }

//+------------------------------------------------------------------+
//| Utilita'                                                          |
//+------------------------------------------------------------------+
double ValoreBuffer(int handle, int buffer, int shift)
  {
   if(handle == INVALID_HANDLE)
      return 0;
   double v[];
   if(CopyBuffer(handle, buffer, shift, 1, v) != 1)
      return 0;
   return v[0];
  }

//+------------------------------------------------------------------+
bool SpreadOk(const MqlTick &t)
  {
   return (InpSpreadMaxPoint <= 0) || (t.ask - t.bid <= InpSpreadMaxPoint * _Point + _Point / 2.0);
  }

//+------------------------------------------------------------------+
bool OrarioOk()
  {
   if(InpOraInizio == InpOraFine)
      return true;
   MqlDateTime d;
   TimeToStruct(TimeCurrent(), d);
   if(InpOraInizio < InpOraFine)
      return d.hour >= InpOraInizio && d.hour < InpOraFine;
   return d.hour >= InpOraInizio || d.hour < InpOraFine; // orario a cavallo della mezzanotte
  }

//+------------------------------------------------------------------+
double DistanzaMinimaBroker()
  {
   double livelloStop   = (double)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_STOPS_LEVEL);
   double livelloFreeze = (double)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_FREEZE_LEVEL);
   return (MathMax(livelloStop, livelloFreeze) + 1.0) * _Point;
  }

//+------------------------------------------------------------------+
double ArrotondaTick(double prezzo, bool versoAlto)
  {
   double tick = SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE);
   if(tick <= 0)
      tick = _Point;
   double n = prezzo / tick;
   n = versoAlto ? MathCeil(n - 1e-8) : MathFloor(n + 1e-8);
   return NormalizeDouble(n * tick, _Digits);
  }

//+------------------------------------------------------------------+
double CalcolaPointPerPip()
  {
   if(InpPointPerPip > 0)
      return InpPointPerPip;

   string simbolo = _Symbol;
   StringToUpper(simbolo);
   if(StringFind(simbolo, "XAU") >= 0 || StringFind(simbolo, "GOLD") >= 0)
      return MathMax(1.0, MathRound(0.10 / _Point)); // oro: 1 pip = 0.10
   if(_Digits == 3 || _Digits == 5)
      return 10.0;
   return 1.0;
  }

//+------------------------------------------------------------------+
void Pannello(bool stopOggi)
  {
   if(!InpPannello)
      return;
   if(MQLInfoInteger(MQL_TESTER) && !MQLInfoInteger(MQL_VISUAL_MODE))
      return;

   string valuta = AccountInfoString(ACCOUNT_CURRENCY);
   double saldo  = AccountInfoDouble(ACCOUNT_BALANCE);
   string trend  = (g_trend > 0) ? "RIALZISTA (solo BUY)" : (g_trend < 0) ? "RIBASSISTA (solo SELL)" : "nessuno (non entra)";

   string testo = "SCALPER TREND\n";
   testo += StringFormat("Trend %s: %s  |  ADX %.1f\n", StringSubstr(EnumToString(InpTFTrend), 7), trend,
                         ValoreBuffer(g_hADX, 0, 1));
   testo += StringFormat("Rischio %.1f%% = %.2f %s per operazione (saldo %.2f)\n",
                         InpRischioPerc, saldo * InpRischioPerc / 100.0, valuta, saldo);
   testo += StringFormat("Oggi: %d operazioni, risultato %.2f %s", g_operazioniOggi, g_pnlOggi, valuta);
   if(InpPerditaMaxGiornoPerc > 0)
      testo += StringFormat("  |  stop del giorno a -%.2f", (saldo - g_pnlOggi) * InpPerditaMaxGiornoPerc / 100.0);
   testo += "\n";

   if(stopOggi)
      testo += "STOP PER OGGI: perdita massima del giorno raggiunta\n";
   else
      if(g_nBuy + g_nSell > 0)
         testo += StringFormat("Operazione %s: %d/%d ordini  |  stop %s  |  profitto %.2f %s\n",
                               g_nBuy > 0 ? "BUY" : "SELL", g_nBuy + g_nSell, g_maxOrdini,
                               DoubleToString(g_stop, _Digits), g_profitto, valuta);
      else
         if(g_segnale != 0)
            testo += StringFormat("Segnale %s: ingresso a %s, stop %s\n", g_segnale > 0 ? "BUY" : "SELL",
                                  DoubleToString(g_livello, _Digits), DoubleToString(g_sl, _Digits));
         else
            testo += OrarioOk() ? "In attesa di un pullback a favore del trend\n" : "Fuori orario\n";
   Comment(testo);
  }
//+------------------------------------------------------------------+
