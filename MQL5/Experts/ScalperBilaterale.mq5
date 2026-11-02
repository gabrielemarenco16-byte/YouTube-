//+------------------------------------------------------------------+
//|                                            ScalperBilaterale.mq5 |
//|  Scalper dinamico bilaterale a paniere                           |
//|                                                                  |
//|  - Ingresso bilaterale costante: un BUY STOP sopra e un SELL     |
//|    STOP sotto il prezzo, sempre vicini (1-2 pips). Seguono il    |
//|    prezzo (inseguimento) finché uno non viene preso: si entra    |
//|    esattamente quando il prezzo si muove.                        |
//|  - Ordini ravvicinati: un nuovo ordine ogni 2 pips nella         |
//|    direzione del movimento.                                      |
//|  - Copertura immediata: se il prezzo si gira, l'ordine opposto   |
//|    ha il lotto dell'esposizione del paniere e la copre subito.   |
//|  - Trailing a paniere: quando il paniere guadagna, insegue il    |
//|    profitto e chiude tutto se ne restituisce una parte.          |
//|  - Dinamico: la distanza dei pendenti si allarga con la          |
//|    volatilità (ATR M1) e la size cresce con il saldo             |
//|    (default 0.01 lotti ogni 1000 di saldo).                      |
//+------------------------------------------------------------------+
#property copyright   "gabrielemarenco16-byte"
#property version     "1.00"
#property description "Scalper bilaterale a paniere: pendenti BUY/SELL STOP a inseguimento,"
#property description "ordini ravvicinati, copertura immediata e trailing sul profitto del paniere."
#property description "Richiede un conto hedging."

#include <Trade\Trade.mqh>

//+------------------------------------------------------------------+
//| Parametri                                                        |
//+------------------------------------------------------------------+
input group "=== Ingresso bilaterale a inseguimento ==="
input double InpDistanzaPips   = 1.0; // Distanza minima dei pendenti dal prezzo (pips)
input double InpFattoreATR     = 0.3; // Distanza dinamica: ATR M1 x fattore se piu' grande (0 = fissa)
input int    InpPeriodoATR     = 14;  // Periodo ATR (M1)
input double InpPassoPips      = 2.0; // Distanza minima tra due ordini nella stessa direzione (pips)
input int    InpSpreadMaxPoint = 50;  // Spread massimo per nuovi ingressi (point, 0 = nessun filtro)

input group "=== Lotti e copertura ==="
input double InpLotto            = 0.01; // Lotto minimo di ogni ingresso
input double InpLottoOgni1000    = 0.01; // Size che cresce col saldo: lotti ogni 1000 di saldo (0 = lotto fisso)
input double InpFattoreCopertura = 1.0;  // Copertura immediata: lotto = esposizione opposta x fattore (0 = off)
input double InpLottoMassimo     = 1.0;  // Lotto massimo per ordine (0 = nessun limite)
input int    InpMaxPosizioni     = 30;   // Posizioni massime nel paniere

input group "=== Trailing a paniere ==="
input double InpTrailingAvvioPips    = 5; // Il trailing parte quando il paniere guadagna X pips (sul lotto base)
input double InpTrailingDistanzaPips = 2; // Chiude tutto se il profitto scende di X pips dal massimo
input double InpPerditaMaxPaniere    = 0; // Chiudi tutto se il paniere perde questa cifra (valuta conto, 0 = off)

input group "=== Varie ==="
input long   InpMagic         = 20261103; // Magic number
input int    InpSlippagePoint = 10;       // Slippage massimo (point)
input double InpPointPerPip   = 0;        // Point in 1 pip (0 = automatico: 10 su forex 5 cifre e oro)
input bool   InpPannello      = true;     // Mostra il pannello sul grafico

//+------------------------------------------------------------------+
//| Stato                                                            |
//+------------------------------------------------------------------+
CTrade   g_trade;
double   g_pipPoints           = 10;   // point in 1 pip
double   g_pip                 = 0;    // 1 pip in prezzo
int      g_handleATR           = INVALID_HANDLE;
bool     g_trailingAttivo      = false;
double   g_picco               = 0;    // profitto massimo raggiunto dal paniere
datetime g_pausaFinoA          = 0;
bool     g_aggiornaCommissioni = true;
double   g_commissioni         = 0;
int      g_nPosPrecedente      = -1;

//--- paniere (riletto a ogni tick)
int      g_nBuy    = 0;
int      g_nSell   = 0;
double   g_volBuy  = 0;
double   g_volSell = 0;
double   g_flutt   = 0;
datetime g_tPrimo  = 0;
double   g_prezziBuy[];
double   g_prezziSell[];
long     g_idPosizioni[];

//+------------------------------------------------------------------+
int OnInit()
  {
   if(InpDistanzaPips <= 0 || InpPassoPips < 0 || InpLotto <= 0 || InpLottoOgni1000 < 0 ||
      InpFattoreCopertura < 0 || InpFattoreATR < 0 || InpPeriodoATR < 1 || InpMaxPosizioni < 2 ||
      InpTrailingAvvioPips <= 0 || InpTrailingDistanzaPips <= 0 ||
      InpTrailingDistanzaPips >= InpTrailingAvvioPips)
     {
      Print("Parametri non validi (la distanza del trailing deve essere minore dell'avvio).");
      return INIT_PARAMETERS_INCORRECT;
     }

   if((ENUM_ACCOUNT_MARGIN_MODE)AccountInfoInteger(ACCOUNT_MARGIN_MODE) != ACCOUNT_MARGIN_MODE_RETAIL_HEDGING)
     {
      Print("Serve un conto HEDGING: il bot tiene aperti BUY e SELL insieme.");
      return INIT_FAILED;
     }

   g_pipPoints = CalcolaPointPerPip();
   g_pip       = g_pipPoints * _Point;

   g_trade.SetExpertMagicNumber((ulong)InpMagic);
   g_trade.SetDeviationInPoints((ulong)InpSlippagePoint);
   g_trade.SetTypeFillingBySymbol(_Symbol);

   if(InpFattoreATR > 0)
     {
      g_handleATR = iATR(_Symbol, PERIOD_M1, InpPeriodoATR);
      if(g_handleATR == INVALID_HANDLE)
         Print("ATR non disponibile: uso la distanza fissa.");
     }

   PrintFormat("Scalper bilaterale su %s: 1 pip = %.0f point, lotto base %.2f, 1 pip sul lotto base = %.2f %s",
               _Symbol, g_pipPoints, LottoBase(), ValorePipPerLotto() * LottoBase(), AccountInfoString(ACCOUNT_CURRENCY));
   return INIT_SUCCEEDED;
  }

//+------------------------------------------------------------------+
void OnDeinit(const int reason)
  {
   if(reason == REASON_REMOVE)
      CancellaPendenti(); // bot tolto dal grafico: niente pendenti lasciati senza gestione
   if(g_handleATR != INVALID_HANDLE)
      IndicatorRelease(g_handleATR);
   Comment("");
  }

//+------------------------------------------------------------------+
void OnTrade()
  {
   g_aggiornaCommissioni = true;
  }

//+------------------------------------------------------------------+
void OnTick()
  {
   LeggiPaniere();
   int nPos = g_nBuy + g_nSell;
   if(nPos != g_nPosPrecedente)
     {
      g_aggiornaCommissioni = true;
      g_nPosPrecedente      = nPos;
     }

   double profitto   = (nPos > 0) ? g_flutt + CommissioniPaniere() : 0;
   double valorePip  = ValorePipPerLotto() * LottoBase(); // 1 pip sul lotto base, in valuta del conto
   double netto      = g_volBuy - g_volSell;
   double mezzoPasso = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP) / 2.0;

   if(nPos > 0)
     {
      //--- trailing a paniere
      if(valorePip > 0)
        {
         double avvio    = InpTrailingAvvioPips * valorePip;
         double distanza = InpTrailingDistanzaPips * valorePip;
         if(!g_trailingAttivo && profitto >= avvio)
           {
            g_trailingAttivo = true;
            g_picco          = profitto;
            PrintFormat("Trailing attivo: paniere a %.2f", profitto);
           }
         if(g_trailingAttivo)
           {
            if(profitto > g_picco)
               g_picco = profitto;
            if(profitto <= g_picco - distanza)
              {
               ChiudiPaniere("trailing", profitto);
               Pannello(profitto, valorePip);
               return;
              }
           }
        }

      //--- protezione facoltativa
      if(InpPerditaMaxPaniere > 0 && profitto <= -InpPerditaMaxPaniere)
        {
         ChiudiPaniere("perdita massima", profitto);
         Pannello(profitto, valorePip);
         return;
        }

      //--- paniere pieno e tutto coperto: non si può più muovere, lo chiudo
      //    (chiudere posizioni bloccate non cambia il risultato)
      if(nPos >= InpMaxPosizioni && MathAbs(netto) < mezzoPasso)
        {
         ChiudiPaniere("paniere pieno e tutto coperto", profitto);
         Pannello(profitto, valorePip);
         return;
        }
     }
   else
     {
      g_trailingAttivo = false;
      g_picco          = 0;
     }

   GestisciPendenti(nPos, netto);
   Pannello(profitto, valorePip);
  }

//+------------------------------------------------------------------+
//| Pendenti bilaterali: un BUY STOP sopra e un SELL STOP sotto      |
//+------------------------------------------------------------------+
void GestisciPendenti(int nPos, double netto)
  {
   if(TimeCurrent() < g_pausaFinoA)
      return;

   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t) || t.ask <= 0 || t.bid <= 0)
      return;

   double mezzoPasso = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP) / 2.0;
   bool   spreadOk   = (InpSpreadMaxPoint <= 0) ||
                       (t.ask - t.bid <= InpSpreadMaxPoint * _Point + _Point / 2.0);

   //--- spread troppo alto o paniere pieno: resta solo il pendente di copertura
   bool vuoiBuy  = true;
   bool vuoiSell = true;
   if(!spreadOk || nPos >= InpMaxPosizioni)
     {
      vuoiBuy  = InpFattoreCopertura > 0 && netto < -mezzoPasso;
      vuoiSell = InpFattoreCopertura > 0 && netto > mezzoPasso;
     }

   double distanza = DistanzaPrezzo();
   double passo    = InpPassoPips * g_pip;

   GestisciLato(ORDER_TYPE_BUY_STOP, vuoiBuy, LivelloBuyStop(t.ask, distanza, passo),
                LottoPendente(ORDER_TYPE_BUY_STOP, netto));
   GestisciLato(ORDER_TYPE_SELL_STOP, vuoiSell, LivelloSellStop(t.bid, distanza, passo),
                LottoPendente(ORDER_TYPE_SELL_STOP, netto));
  }

//+------------------------------------------------------------------+
//| Un lato: piazza, sostituisce (lotto cambiato) o avvicina al      |
//| prezzo il pendente (inseguimento)                                |
//+------------------------------------------------------------------+
void GestisciLato(ENUM_ORDER_TYPE tipo, bool vuoi, double livello, double lotto)
  {
   ulong  ticket = 0;
   double prezzo = 0;
   double volume = 0;

   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      ulong tk = OrderGetTicket(i);
      if(tk == 0)
         continue;
      if(OrderGetInteger(ORDER_MAGIC) != InpMagic || OrderGetString(ORDER_SYMBOL) != _Symbol)
         continue;
      if((ENUM_ORDER_TYPE)OrderGetInteger(ORDER_TYPE) != tipo)
         continue;
      if(!vuoi || ticket != 0) // non serve, oppure è un doppione
        {
         Cancella(tk);
         continue;
        }
      ticket = tk;
      prezzo = OrderGetDouble(ORDER_PRICE_OPEN);
      volume = OrderGetDouble(ORDER_VOLUME_CURRENT);
     }

   if(!vuoi || TimeCurrent() < g_pausaFinoA)
      return;

   if(ticket == 0)
     {
      Piazza(tipo, livello, lotto);
      return;
     }

   //--- lotto da cambiare (es. è diventato un ordine di copertura): sostituisco
   if(MathAbs(volume - lotto) >= SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP) / 2.0)
     {
      if(Cancella(ticket))
         Piazza(tipo, livello, lotto);
      return;
     }

   //--- inseguimento: il pendente si avvicina al prezzo, non si allontana mai
   double soglia = MathMax(0.3 * g_pip, SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE));
   bool avvicina = (tipo == ORDER_TYPE_BUY_STOP) ? (prezzo - livello >= soglia)
                                                 : (livello - prezzo >= soglia);
   if(avvicina)
      Modifica(ticket, livello);
  }

//+------------------------------------------------------------------+
//| Livelli dei pendenti, distanziati di "passo" dagli ordini aperti |
//| nella stessa direzione                                           |
//+------------------------------------------------------------------+
double LivelloBuyStop(double ask, double distanza, double passo)
  {
   double livello = ask + distanza;
   if(passo > 0)
      for(int i = 0; i < ArraySize(g_prezziBuy); i++) // prezzi in ordine crescente
         if(MathAbs(livello - g_prezziBuy[i]) < passo)
            livello = g_prezziBuy[i] + passo;
   return ArrotondaTick(livello, true);
  }

//+------------------------------------------------------------------+
double LivelloSellStop(double bid, double distanza, double passo)
  {
   double livello = bid - distanza;
   if(passo > 0)
      for(int i = ArraySize(g_prezziSell) - 1; i >= 0; i--) // dal più alto al più basso
         if(MathAbs(livello - g_prezziSell[i]) < passo)
            livello = g_prezziSell[i] - passo;
   return ArrotondaTick(livello, false);
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
//| Distanza dinamica dei pendenti dal prezzo                        |
//+------------------------------------------------------------------+
double DistanzaPrezzo()
  {
   double d = InpDistanzaPips * g_pip;
   if(g_handleATR != INVALID_HANDLE)
     {
      double atr[];
      if(CopyBuffer(g_handleATR, 0, 1, 1, atr) == 1 && atr[0] > 0)
         d = MathMax(d, atr[0] * InpFattoreATR);
     }
   //--- mai sotto la distanza minima del broker
   double livelloStop   = (double)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_STOPS_LEVEL);
   double livelloFreeze = (double)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_FREEZE_LEVEL);
   double minimo        = (MathMax(livelloStop, livelloFreeze) + 1.0) * _Point;
   return MathMax(d, minimo);
  }

//+------------------------------------------------------------------+
//| Lotto del pendente: base, oppure copertura dell'esposizione      |
//+------------------------------------------------------------------+
double LottoPendente(ENUM_ORDER_TYPE tipo, double netto)
  {
   double lotto = LottoBase();
   if(InpFattoreCopertura > 0)
     {
      if(tipo == ORDER_TYPE_BUY_STOP && netto < 0)
         lotto = MathMax(lotto, -netto * InpFattoreCopertura);
      if(tipo == ORDER_TYPE_SELL_STOP && netto > 0)
         lotto = MathMax(lotto, netto * InpFattoreCopertura);
     }
   return NormalizzaLotto(lotto);
  }

//+------------------------------------------------------------------+
double LottoBase()
  {
   double lotto = InpLotto;
   if(InpLottoOgni1000 > 0) // la size cresce con il saldo, mai sotto il lotto minimo
      lotto = MathMax(InpLotto, AccountInfoDouble(ACCOUNT_BALANCE) / 1000.0 * InpLottoOgni1000);
   return NormalizzaLotto(lotto);
  }

//+------------------------------------------------------------------+
double NormalizzaLotto(double lotto)
  {
   double passo   = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP);
   double minimo  = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MIN);
   double massimo = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MAX);

   if(InpLottoMassimo > 0 && lotto > InpLottoMassimo)
      lotto = InpLottoMassimo;
   if(passo > 0)
      lotto = MathRound(lotto / passo) * passo;
   if(lotto < minimo)
      lotto = minimo;
   if(massimo > 0 && lotto > massimo)
      lotto = massimo;

   int cifre = 2;
   if(passo > 0)
      cifre = (int)MathMax(0.0, MathCeil(-MathLog10(passo) - 1e-9));
   return NormalizeDouble(lotto, cifre);
  }

//+------------------------------------------------------------------+
//| Operazioni                                                       |
//+------------------------------------------------------------------+
bool Piazza(ENUM_ORDER_TYPE tipo, double livello, double lotto)
  {
   bool   copertura = lotto > LottoBase() + SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP) / 2.0;
   string commento  = copertura ? "SB copertura" : "SB ingresso";

   //--- per gli ingressi controllo il margine (le coperture riducono il rischio)
   if(!copertura)
     {
      double margine = 0;
      ENUM_ORDER_TYPE mercato = (tipo == ORDER_TYPE_BUY_STOP) ? ORDER_TYPE_BUY : ORDER_TYPE_SELL;
      if(!OrderCalcMargin(mercato, _Symbol, lotto, livello, margine) ||
         margine > AccountInfoDouble(ACCOUNT_MARGIN_FREE))
        {
         PrintFormat("Margine insufficiente per %.2f lotti: pendente non piazzato.", lotto);
         g_pausaFinoA = TimeCurrent() + 60;
         return false;
        }
     }

   bool ok = (tipo == ORDER_TYPE_BUY_STOP)
             ? g_trade.BuyStop(lotto, livello, _Symbol, 0, 0, ORDER_TIME_GTC, 0, commento)
             : g_trade.SellStop(lotto, livello, _Symbol, 0, 0, ORDER_TIME_GTC, 0, commento);
   return Esito(ok, "piazzamento pendente");
  }

//+------------------------------------------------------------------+
bool Modifica(ulong ticket, double livello)
  {
   bool ok = g_trade.OrderModify(ticket, livello, 0, 0, ORDER_TIME_GTC, 0);
   return Esito(ok, "spostamento pendente");
  }

//+------------------------------------------------------------------+
bool Cancella(ulong ticket)
  {
   bool ok = g_trade.OrderDelete(ticket);
   return Esito(ok, "cancellazione pendente");
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
void ChiudiPaniere(const string motivo, double profitto)
  {
   CancellaPendenti(); // prima i pendenti, così non entrano mentre chiudo

   int  chiuse = 0;
   bool tutto  = true;
   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0)
         continue;
      if(PositionGetInteger(POSITION_MAGIC) != InpMagic || PositionGetString(POSITION_SYMBOL) != _Symbol)
         continue;
      if(g_trade.PositionClose(ticket))
         chiuse++;
      else
        {
         tutto = false;
         PrintFormat("Errore chiusura posizione: %u %s", g_trade.ResultRetcode(), g_trade.ResultRetcodeDescription());
        }
     }

   PrintFormat("Paniere chiuso (%s): %d posizioni, risultato circa %.2f %s%s", motivo, chiuse, profitto,
               AccountInfoString(ACCOUNT_CURRENCY), tutto ? "" : " - alcune posizioni non chiuse, riprovo");
   if(tutto)
     {
      g_trailingAttivo = false;
      g_picco          = 0;
     }
   g_aggiornaCommissioni = true;
  }

//+------------------------------------------------------------------+
//| Lettura del paniere                                              |
//+------------------------------------------------------------------+
void LeggiPaniere()
  {
   g_nBuy    = 0;
   g_nSell   = 0;
   g_volBuy  = 0;
   g_volSell = 0;
   g_flutt   = 0;
   g_tPrimo  = 0;
   ArrayResize(g_prezziBuy, 0);
   ArrayResize(g_prezziSell, 0);
   ArrayResize(g_idPosizioni, 0);

   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0)
         continue;
      if(PositionGetInteger(POSITION_MAGIC) != InpMagic || PositionGetString(POSITION_SYMBOL) != _Symbol)
         continue;

      double volume = PositionGetDouble(POSITION_VOLUME);
      double prezzo = PositionGetDouble(POSITION_PRICE_OPEN);
      g_flutt += PositionGetDouble(POSITION_PROFIT) + PositionGetDouble(POSITION_SWAP);

      datetime t = (datetime)PositionGetInteger(POSITION_TIME);
      if(g_tPrimo == 0 || t < g_tPrimo)
         g_tPrimo = t;

      int n = ArraySize(g_idPosizioni);
      ArrayResize(g_idPosizioni, n + 1);
      g_idPosizioni[n] = PositionGetInteger(POSITION_IDENTIFIER);

      if(PositionGetInteger(POSITION_TYPE) == POSITION_TYPE_BUY)
        {
         g_nBuy++;
         g_volBuy += volume;
         int k = ArraySize(g_prezziBuy);
         ArrayResize(g_prezziBuy, k + 1);
         g_prezziBuy[k] = prezzo;
        }
      else
        {
         g_nSell++;
         g_volSell += volume;
         int k = ArraySize(g_prezziSell);
         ArrayResize(g_prezziSell, k + 1);
         g_prezziSell[k] = prezzo;
        }
     }
   ArraySort(g_prezziBuy);
   ArraySort(g_prezziSell);
  }

//+------------------------------------------------------------------+
//| Commissioni già pagate dalle posizioni aperte del paniere        |
//+------------------------------------------------------------------+
double CommissioniPaniere()
  {
   if(g_tPrimo == 0)
     {
      g_commissioni = 0;
      return 0;
     }
   if(!g_aggiornaCommissioni)
      return g_commissioni;
   if(!HistorySelect(g_tPrimo, TimeCurrent() + 86400))
      return g_commissioni;

   double somma = 0;
   int    n     = ArraySize(g_idPosizioni);
   for(int i = HistoryDealsTotal() - 1; i >= 0; i--)
     {
      ulong deal = HistoryDealGetTicket(i);
      if(deal == 0)
         continue;
      long posizione = HistoryDealGetInteger(deal, DEAL_POSITION_ID);
      for(int k = 0; k < n; k++)
        {
         if(g_idPosizioni[k] == posizione)
           {
            somma += HistoryDealGetDouble(deal, DEAL_COMMISSION) + HistoryDealGetDouble(deal, DEAL_FEE);
            break;
           }
        }
     }
   g_commissioni         = somma;
   g_aggiornaCommissioni = false;
   return somma;
  }

//+------------------------------------------------------------------+
//| Pip                                                              |
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
double ValorePipPerLotto()
  {
   double valoreTick = SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_VALUE);
   double dimTick    = SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE);
   if(valoreTick <= 0 || dimTick <= 0)
      return 0;
   return g_pip / dimTick * valoreTick;
  }

//+------------------------------------------------------------------+
void Pannello(double profitto, double valorePip)
  {
   if(!InpPannello)
      return;
   if(MQLInfoInteger(MQL_TESTER) && !MQLInfoInteger(MQL_VISUAL_MODE))
      return;

   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t))
      return;
   string valuta = AccountInfoString(ACCOUNT_CURRENCY);

   string testo = "SCALPER BILATERALE A PANIERE\n";
   testo += StringFormat("Spread %.1f pips  |  distanza pendenti %.1f pips  |  passo %.1f pips\n",
                         (t.ask - t.bid) / g_pip, DistanzaPrezzo() / g_pip, InpPassoPips);
   testo += StringFormat("Lotto base %.2f  (saldo %.2f %s)\n", LottoBase(), AccountInfoDouble(ACCOUNT_BALANCE), valuta);
   testo += StringFormat("BUY %d (%.2f lotti)  |  SELL %d (%.2f lotti)  |  netto %.2f\n",
                         g_nBuy, g_volBuy, g_nSell, g_volSell, g_volBuy - g_volSell);
   testo += StringFormat("Paniere %.2f %s", profitto, valuta);
   if(g_trailingAttivo)
      testo += StringFormat("  |  trailing attivo: massimo %.2f, chiude sotto %.2f",
                            g_picco, g_picco - InpTrailingDistanzaPips * valorePip);
   else
      testo += StringFormat("  |  trailing parte da %.2f", InpTrailingAvvioPips * valorePip);
   Comment(testo + "\n");
  }
//+------------------------------------------------------------------+
