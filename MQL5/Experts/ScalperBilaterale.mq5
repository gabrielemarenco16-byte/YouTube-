//+------------------------------------------------------------------+
//|                                            ScalperBilaterale.mq5 |
//|  Scalper rapido direzionale che opera su entrambi i lati,        |
//|  uno alla volta (mai BUY e SELL insieme)                         |
//|                                                                  |
//|  - Senza operazione aperta: un BUY STOP sopra e un SELL STOP     |
//|    sotto il prezzo (1 pip) che seguono il prezzo.                |
//|  - Il primo che viene preso decide la direzione: l'altro viene   |
//|    cancellato subito.                                            |
//|  - Massimo 3 ordini, a 2 pips l'uno dall'altro, nella direzione  |
//|    del movimento.                                                |
//|  - Trailing stop dinamico vicino al prezzo, uguale per tutti gli |
//|    ordini: quando viene preso l'operazione finisce e si riparte  |
//|    subito con i nuovi pendenti, BUY o SELL.                      |
//|  - Size che cresce con il saldo (0.01 lotti ogni 1000).          |
//+------------------------------------------------------------------+
#property copyright   "gabrielemarenco16-byte"
#property version     "2.00"
#property description "Scalper rapido direzionale: BUY o SELL, mai insieme. Pendenti di ingresso a inseguimento,"
#property description "massimo 3 ordini a 2 pips, trailing stop dinamico vicino al prezzo, size in base al saldo."

#include <Trade\Trade.mqh>

enum ENUM_LATI
  {
   LATI_ENTRAMBI  = 0, // BUY e SELL (uno alla volta)
   LATI_SOLO_BUY  = 1, // Solo BUY
   LATI_SOLO_SELL = 2  // Solo SELL
  };

//+------------------------------------------------------------------+
//| Parametri                                                        |
//+------------------------------------------------------------------+
input group "=== Ingresso (BUY o SELL, mai insieme) ==="
input ENUM_LATI InpLati           = LATI_ENTRAMBI; // Lati su cui operare
input double    InpDistanzaPips   = 1.0;           // Distanza dei pendenti di ingresso dal prezzo (pips)
input double    InpFattoreATR     = 0.3;           // Distanza dinamica: ATR M1 x fattore se piu' grande (0 = fissa)
input int       InpSpreadMaxPoint = 50;            // Spread massimo per entrare (point, 0 = nessun filtro)

input group "=== Ordini e lotti ==="
input int    InpMaxOrdini     = 3;    // Ordini massimi per operazione
input double InpPassoPips     = 2.0;  // Distanza tra gli ordini (pips, nella direzione del movimento)
input double InpLotto         = 0.01; // Lotto minimo per ordine
input double InpLottoOgni1000 = 0.01; // Size che cresce col saldo: lotti ogni 1000 di saldo (0 = lotto fisso)
input double InpLottoMassimo  = 1.0;  // Lotto massimo per ordine (0 = nessun limite)

input group "=== Trailing stop dinamico ==="
input double InpTrailingPips       = 2.0; // Distanza minima dello stop dal prezzo (pips)
input double InpTrailingFattoreATR = 0.5; // Distanza dinamica: ATR M1 x fattore se piu' grande (0 = fissa)
input double InpTrailingPassoPips  = 0.2; // Sposta lo stop quando puo' avanzare di almeno (pips)

input group "=== Varie ==="
input int    InpPeriodoATR    = 14;       // Periodo ATR (M1)
input long   InpMagic         = 20261103; // Magic number
input int    InpSlippagePoint = 10;       // Slippage massimo (point)
input double InpPointPerPip   = 0;        // Point in 1 pip (0 = automatico: 10 su forex 5 cifre e oro)
input bool   InpPannello      = true;     // Mostra il pannello sul grafico

//+------------------------------------------------------------------+
//| Stato                                                            |
//+------------------------------------------------------------------+
CTrade   g_trade;
double   g_pipPoints  = 10;   // point in 1 pip
double   g_pip        = 0;    // 1 pip in prezzo
int      g_handleATR  = INVALID_HANDLE;
datetime g_pausaFinoA = 0;

//--- operazione aperta (riletta a ogni tick)
int    g_nBuy          = 0;
int    g_nSell         = 0;
double g_prezzoBuyMax  = 0;   // ingresso BUY piu' alto
double g_prezzoSellMin = 0;   // ingresso SELL piu' basso
double g_stop          = 0;   // stop attuale dell'operazione
double g_profitto      = 0;

//+------------------------------------------------------------------+
int OnInit()
  {
   if(InpDistanzaPips <= 0 || InpPassoPips <= 0 || InpMaxOrdini < 1 || InpLotto <= 0 ||
      InpLottoOgni1000 < 0 || InpFattoreATR < 0 || InpTrailingPips <= 0 ||
      InpTrailingFattoreATR < 0 || InpTrailingPassoPips < 0 || InpPeriodoATR < 1)
     {
      Print("Parametri non validi: controlla gli input del bot.");
      return INIT_PARAMETERS_INCORRECT;
     }

   //--- con un conto netting i 3 ordini diventerebbero una sola posizione
   if((ENUM_ACCOUNT_MARGIN_MODE)AccountInfoInteger(ACCOUNT_MARGIN_MODE) != ACCOUNT_MARGIN_MODE_RETAIL_HEDGING)
     {
      Print("Serve un conto HEDGING: su netting i 3 ordini si fonderebbero in una sola posizione.");
      return INIT_FAILED;
     }

   g_pipPoints = CalcolaPointPerPip();
   g_pip       = g_pipPoints * _Point;

   g_trade.SetExpertMagicNumber((ulong)InpMagic);
   g_trade.SetDeviationInPoints((ulong)InpSlippagePoint);
   g_trade.SetTypeFillingBySymbol(_Symbol);

   if(InpFattoreATR > 0 || InpTrailingFattoreATR > 0)
     {
      g_handleATR = iATR(_Symbol, PERIOD_M1, InpPeriodoATR);
      if(g_handleATR == INVALID_HANDLE)
         Print("ATR non disponibile: uso le distanze fisse.");
     }

   PrintFormat("Scalper direzionale su %s: 1 pip = %.0f point, lotto %.2f per ordine, max %d ordini a %.1f pips",
               _Symbol, g_pipPoints, LottoBase(), InpMaxOrdini, InpPassoPips);
   return INIT_SUCCEEDED;
  }

//+------------------------------------------------------------------+
void OnDeinit(const int reason)
  {
   if(reason == REASON_REMOVE)
      CancellaPendenti(-1); // bot tolto dal grafico: nessun pendente lasciato senza gestione
   if(g_handleATR != INVALID_HANDLE)
      IndicatorRelease(g_handleATR);
   Comment("");
  }

//+------------------------------------------------------------------+
//| Appena entra un lato cancello subito il pendente dell'altro      |
//+------------------------------------------------------------------+
void OnTrade()
  {
   LeggiPosizioni();
   if(g_nBuy > 0 && g_nSell == 0)
      CancellaPendenti(ORDER_TYPE_SELL_STOP);
   if(g_nSell > 0 && g_nBuy == 0)
      CancellaPendenti(ORDER_TYPE_BUY_STOP);
  }

//+------------------------------------------------------------------+
void OnTick()
  {
   LeggiPosizioni();

   //--- mai BUY e SELL insieme (es. due pendenti presi nello stesso istante)
   if(g_nBuy > 0 && g_nSell > 0)
     {
      ChiudiLatoOpposto();
      return;
     }

   if(g_nBuy == 0 && g_nSell == 0)
      GestisciIngresso();
   else
     {
      ENUM_POSITION_TYPE dir = (g_nBuy > 0) ? POSITION_TYPE_BUY : POSITION_TYPE_SELL;
      CancellaPendenti(dir == POSITION_TYPE_BUY ? ORDER_TYPE_SELL_STOP : ORDER_TYPE_BUY_STOP);
      Trailing(dir);
      GestisciAggiunte(dir);
     }

   Pannello();
  }

//+------------------------------------------------------------------+
//| Nessuna operazione: BUY STOP sopra e SELL STOP sotto il prezzo,  |
//| con lo stop gia' attaccato, che seguono il prezzo                |
//+------------------------------------------------------------------+
void GestisciIngresso()
  {
   if(TimeCurrent() < g_pausaFinoA)
      return;
   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t) || t.ask <= 0 || t.bid <= 0)
      return;

   double spread   = t.ask - t.bid;
   bool   spreadOk = SpreadOk(spread);
   double distanza = DistanzaIngresso();
   double trailing = DistanzaTrailing();
   double lotto    = LottoBase();

   double livBuy  = ArrotondaTick(t.ask + distanza, true);
   double slBuy   = ArrotondaTick(livBuy - spread - trailing, false);
   double livSell = ArrotondaTick(t.bid - distanza, false);
   double slSell  = ArrotondaTick(livSell + spread + trailing, true);

   GestisciPendente(ORDER_TYPE_BUY_STOP, spreadOk && InpLati != LATI_SOLO_SELL, livBuy, slBuy, lotto, true);
   GestisciPendente(ORDER_TYPE_SELL_STOP, spreadOk && InpLati != LATI_SOLO_BUY, livSell, slSell, lotto, true);
  }

//+------------------------------------------------------------------+
//| Operazione aperta: ordine successivo a "passo" pips dall'ultimo, |
//| nella stessa direzione, fino al massimo degli ordini             |
//+------------------------------------------------------------------+
void GestisciAggiunte(ENUM_POSITION_TYPE dir)
  {
   ENUM_ORDER_TYPE tipo = (dir == POSITION_TYPE_BUY) ? ORDER_TYPE_BUY_STOP : ORDER_TYPE_SELL_STOP;
   int             n    = (dir == POSITION_TYPE_BUY) ? g_nBuy : g_nSell;

   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t) || t.ask <= 0 || t.bid <= 0)
      return;

   double spread   = t.ask - t.bid;
   bool   vuoi     = SpreadOk(spread) && n < InpMaxOrdini;
   double minimo   = DistanzaMinimaBroker();
   double passo    = InpPassoPips * g_pip;
   double trailing = DistanzaTrailing();

   double livello, sl;
   if(dir == POSITION_TYPE_BUY)
     {
      livello = ArrotondaTick(MathMax(g_prezzoBuyMax + passo, t.ask + minimo), true);
      sl      = ArrotondaTick(livello - spread - trailing, false);
     }
   else
     {
      livello = ArrotondaTick(MathMin(g_prezzoSellMin - passo, t.bid - minimo), false);
      sl      = ArrotondaTick(livello + spread + trailing, true);
     }
   GestisciPendente(tipo, vuoi, livello, sl, LottoBase(), false);
  }

//+------------------------------------------------------------------+
//| Un pendente: lo piazza, lo sostituisce se il lotto e' cambiato,  |
//| lo sposta al nuovo livello. Con "soloVersoPrezzo" si avvicina al |
//| prezzo e non si allontana mai (inseguimento).                    |
//+------------------------------------------------------------------+
void GestisciPendente(ENUM_ORDER_TYPE tipo, bool vuoi, double livello, double sl, double lotto, bool soloVersoPrezzo)
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
      if(!vuoi || ticket != 0) // non serve, oppure e' un doppione
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
      Piazza(tipo, livello, sl, lotto);
      return;
     }

   if(MathAbs(volume - lotto) >= SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP) / 2.0)
     {
      if(Cancella(ticket))
         Piazza(tipo, livello, sl, lotto);
      return;
     }

   double soglia = MathMax(0.3 * g_pip, SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE));
   double verso  = (tipo == ORDER_TYPE_BUY_STOP) ? prezzo - livello : livello - prezzo; // > 0 = piu' vicino al prezzo
   bool   sposta = soloVersoPrezzo ? (verso >= soglia) : (MathAbs(verso) >= soglia);
   if(sposta)
      Modifica(ticket, livello, sl);
  }

//+------------------------------------------------------------------+
//| Trailing stop unico per tutti gli ordini dell'operazione: segue  |
//| il prezzo a distanza dinamica e si muove solo a favore           |
//+------------------------------------------------------------------+
void Trailing(ENUM_POSITION_TYPE dir)
  {
   if(TimeCurrent() < g_pausaFinoA)
      return;
   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t) || t.ask <= 0 || t.bid <= 0)
      return;

   double trailing = DistanzaTrailing();
   double passo    = MathMax(InpTrailingPassoPips * g_pip, SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE));
   double minimo   = DistanzaMinimaBroker();

   //--- livello comune: il piu' stretto tra gli stop attuali e il prezzo meno la distanza
   double nuovo = (dir == POSITION_TYPE_BUY) ? ArrotondaTick(t.bid - trailing, false)
                                             : ArrotondaTick(t.ask + trailing, true);
   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      if(!SelezionaPosizione(i, dir))
         continue;
      double sl = PositionGetDouble(POSITION_SL);
      if(sl <= 0)
         continue;
      if(dir == POSITION_TYPE_BUY && sl > nuovo)
         nuovo = sl;
      if(dir == POSITION_TYPE_SELL && sl < nuovo)
         nuovo = sl;
     }

   //--- troppo vicino al prezzo per il broker: aspetto
   if(dir == POSITION_TYPE_BUY && t.bid - nuovo < minimo)
      return;
   if(dir == POSITION_TYPE_SELL && nuovo - t.ask < minimo)
      return;

   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      if(!SelezionaPosizione(i, dir))
         continue;
      ulong  ticket = (ulong)PositionGetInteger(POSITION_TICKET);
      double sl     = PositionGetDouble(POSITION_SL);
      double tp     = PositionGetDouble(POSITION_TP);
      bool migliora = (sl <= 0) ||
                      (dir == POSITION_TYPE_BUY ? nuovo - sl >= passo : sl - nuovo >= passo);
      if(!migliora)
         continue;
      if(!Esito(g_trade.PositionModify(ticket, nuovo, tp), "spostamento stop"))
         return;
     }
  }

//+------------------------------------------------------------------+
//| Seleziona la posizione i se e' del bot e della direzione data    |
//+------------------------------------------------------------------+
bool SelezionaPosizione(int i, ENUM_POSITION_TYPE dir)
  {
   ulong ticket = PositionGetTicket(i);
   if(ticket == 0)
      return false;
   if(PositionGetInteger(POSITION_MAGIC) != InpMagic || PositionGetString(POSITION_SYMBOL) != _Symbol)
      return false;
   return (ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE) == dir;
  }

//+------------------------------------------------------------------+
//| Se ci sono BUY e SELL insieme tengo la direzione entrata per     |
//| prima e chiudo l'altra                                           |
//+------------------------------------------------------------------+
void ChiudiLatoOpposto()
  {
   long tPrimo    = LONG_MAX;
   long tipoPrimo = -1;
   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0)
         continue;
      if(PositionGetInteger(POSITION_MAGIC) != InpMagic || PositionGetString(POSITION_SYMBOL) != _Symbol)
         continue;
      long tms = PositionGetInteger(POSITION_TIME_MSC);
      if(tms < tPrimo)
        {
         tPrimo    = tms;
         tipoPrimo = PositionGetInteger(POSITION_TYPE);
        }
     }

   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0)
         continue;
      if(PositionGetInteger(POSITION_MAGIC) != InpMagic || PositionGetString(POSITION_SYMBOL) != _Symbol)
         continue;
      if(PositionGetInteger(POSITION_TYPE) != tipoPrimo)
         Esito(g_trade.PositionClose(ticket), "chiusura lato opposto");
     }
   Print("BUY e SELL aperti insieme: chiuso il lato entrato per secondo.");
  }

//+------------------------------------------------------------------+
//| Lettura dell'operazione aperta                                   |
//+------------------------------------------------------------------+
void LeggiPosizioni()
  {
   g_nBuy          = 0;
   g_nSell         = 0;
   g_prezzoBuyMax  = 0;
   g_prezzoSellMin = 0;
   g_stop          = 0;
   g_profitto      = 0;

   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0)
         continue;
      if(PositionGetInteger(POSITION_MAGIC) != InpMagic || PositionGetString(POSITION_SYMBOL) != _Symbol)
         continue;

      double prezzo = PositionGetDouble(POSITION_PRICE_OPEN);
      double sl     = PositionGetDouble(POSITION_SL);
      g_profitto += PositionGetDouble(POSITION_PROFIT) + PositionGetDouble(POSITION_SWAP);

      if(PositionGetInteger(POSITION_TYPE) == POSITION_TYPE_BUY)
        {
         g_nBuy++;
         if(prezzo > g_prezzoBuyMax)
            g_prezzoBuyMax = prezzo;
         if(sl > g_stop)
            g_stop = sl;
        }
      else
        {
         g_nSell++;
         if(g_prezzoSellMin == 0 || prezzo < g_prezzoSellMin)
            g_prezzoSellMin = prezzo;
         if(sl > 0 && (g_stop == 0 || sl < g_stop))
            g_stop = sl;
        }
     }
  }

//+------------------------------------------------------------------+
//| Distanze                                                         |
//+------------------------------------------------------------------+
double ValoreATR()
  {
   if(g_handleATR == INVALID_HANDLE)
      return 0;
   double atr[];
   if(CopyBuffer(g_handleATR, 0, 1, 1, atr) != 1)
      return 0;
   return atr[0];
  }

//+------------------------------------------------------------------+
double DistanzaMinimaBroker()
  {
   double livelloStop   = (double)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_STOPS_LEVEL);
   double livelloFreeze = (double)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_FREEZE_LEVEL);
   return (MathMax(livelloStop, livelloFreeze) + 1.0) * _Point;
  }

//+------------------------------------------------------------------+
double DistanzaIngresso()
  {
   double d = MathMax(InpDistanzaPips * g_pip, ValoreATR() * InpFattoreATR);
   return MathMax(d, DistanzaMinimaBroker());
  }

//+------------------------------------------------------------------+
double DistanzaTrailing()
  {
   double d = MathMax(InpTrailingPips * g_pip, ValoreATR() * InpTrailingFattoreATR);
   return MathMax(d, DistanzaMinimaBroker());
  }

//+------------------------------------------------------------------+
bool SpreadOk(double spread)
  {
   return (InpSpreadMaxPoint <= 0) || (spread <= InpSpreadMaxPoint * _Point + _Point / 2.0);
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
//| Lotti: la size cresce con il saldo, mai sotto il lotto minimo    |
//+------------------------------------------------------------------+
double LottoBase()
  {
   double lotto = InpLotto;
   if(InpLottoOgni1000 > 0)
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
//| Operazioni sui pendenti                                          |
//+------------------------------------------------------------------+
bool Piazza(ENUM_ORDER_TYPE tipo, double livello, double sl, double lotto)
  {
   double          margine = 0;
   ENUM_ORDER_TYPE mercato = (tipo == ORDER_TYPE_BUY_STOP) ? ORDER_TYPE_BUY : ORDER_TYPE_SELL;
   if(!OrderCalcMargin(mercato, _Symbol, lotto, livello, margine) ||
      margine > AccountInfoDouble(ACCOUNT_MARGIN_FREE))
     {
      PrintFormat("Margine insufficiente per %.2f lotti: pendente non piazzato.", lotto);
      g_pausaFinoA = TimeCurrent() + 60;
      return false;
     }

   bool ok = (tipo == ORDER_TYPE_BUY_STOP)
             ? g_trade.BuyStop(lotto, livello, _Symbol, sl, 0, ORDER_TIME_GTC, 0, "SR buy")
             : g_trade.SellStop(lotto, livello, _Symbol, sl, 0, ORDER_TIME_GTC, 0, "SR sell");
   return Esito(ok, "piazzamento pendente");
  }

//+------------------------------------------------------------------+
bool Modifica(ulong ticket, double livello, double sl)
  {
   return Esito(g_trade.OrderModify(ticket, livello, sl, 0, ORDER_TIME_GTC, 0), "spostamento pendente");
  }

//+------------------------------------------------------------------+
bool Cancella(ulong ticket)
  {
   return Esito(g_trade.OrderDelete(ticket), "cancellazione pendente");
  }

//+------------------------------------------------------------------+
//| Cancella i pendenti del bot di un tipo (-1 = tutti)              |
//+------------------------------------------------------------------+
void CancellaPendenti(int tipo)
  {
   for(int i = OrdersTotal() - 1; i >= 0; i--)
     {
      ulong tk = OrderGetTicket(i);
      if(tk == 0)
         continue;
      if(OrderGetInteger(ORDER_MAGIC) != InpMagic || OrderGetString(ORDER_SYMBOL) != _Symbol)
         continue;
      if(tipo >= 0 && OrderGetInteger(ORDER_TYPE) != tipo)
         continue;
      g_trade.OrderDelete(tk);
     }
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
void Pannello()
  {
   if(!InpPannello)
      return;
   if(MQLInfoInteger(MQL_TESTER) && !MQLInfoInteger(MQL_VISUAL_MODE))
      return;

   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t))
      return;
   string valuta = AccountInfoString(ACCOUNT_CURRENCY);

   string testo = "SCALPER RAPIDO DIREZIONALE\n";
   testo += StringFormat("Spread %.1f pips  |  ingresso a %.1f pips  |  trailing a %.1f pips\n",
                         (t.ask - t.bid) / g_pip, DistanzaIngresso() / g_pip, DistanzaTrailing() / g_pip);
   testo += StringFormat("Lotto %.2f per ordine (saldo %.2f %s)  |  max %d ordini a %.1f pips\n",
                         LottoBase(), AccountInfoDouble(ACCOUNT_BALANCE), valuta, InpMaxOrdini, InpPassoPips);
   if(g_nBuy > 0 || g_nSell > 0)
      testo += StringFormat("Operazione %s: %d/%d ordini  |  stop %s  |  profitto %.2f %s\n",
                            g_nBuy > 0 ? "BUY" : "SELL", g_nBuy + g_nSell, InpMaxOrdini,
                            DoubleToString(g_stop, _Digits), g_profitto, valuta);
   else
      testo += "Nessuna operazione: pendenti BUY e SELL in attesa\n";
   Comment(testo);
  }
//+------------------------------------------------------------------+
