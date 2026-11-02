//+------------------------------------------------------------------+
//|                                            MartingalaTreFasi.mq5 |
//|  Martingala a griglia con 3 fasi decise dall'ampiezza del range: |
//|                                                                  |
//|  FASE 1 laterale      range <= 300 pips  -> ordini ogni 30 pips  |
//|                                             SL 500 point         |
//|  FASE 2 allargamento  range <  2000 pips -> ordini ogni 150 pips |
//|                       (range/8 se più grande) SL 650 point       |
//|  FASE 3               range >= 2000 pips -> nessun nuovo ordine  |
//+------------------------------------------------------------------+
#property copyright   "gabrielemarenco16-byte"
#property version     "1.00"
#property description "Martingala a 3 fasi: laterale (griglia 30 pips, SL 500 point),"
#property description "allargamento sotto i 2000 pips (griglia 150 pips o range/8, SL 650 point),"
#property description "oltre i 2000 pips nessun nuovo ordine. Richiede un conto hedging."

#include <Trade\Trade.mqh>

//--- fasi di mercato
enum ENUM_FASE
  {
   FASE_1_LATERALE     = 1, // Fase 1 - laterale
   FASE_2_ALLARGAMENTO = 2, // Fase 2 - allargamento
   FASE_3_STOP         = 3  // Fase 3 - stop
  };

//--- direzione degli ingressi
enum ENUM_DIREZIONE
  {
   DIR_AUTOMATICA = 0, // Automatica (BUY in basso, SELL in alto)
   DIR_SOLO_BUY   = 1, // Solo BUY
   DIR_SOLO_SELL  = 2  // Solo SELL
  };

//+------------------------------------------------------------------+
//| Parametri                                                        |
//+------------------------------------------------------------------+
input group "=== Riconoscimento fasi (ampiezza del range) ==="
input ENUM_TIMEFRAMES InpTFRange              = PERIOD_H1; // Timeframe su cui misurare il range
input int             InpBarreRange           = 24;        // Candele del range (24 x H1 = ultime 24 ore)
input double          InpRangeLateraleMaxPips = 300;       // FASE 1 laterale se il range e' <= pips
input double          InpRangeMaxPips         = 2000;      // FASE 2 se il range e' < pips, oltre FASE 3 (niente)

input group "=== FASE 1 - Mercato laterale ==="
input double InpStepFase1Pips = 30;  // Distanza tra gli ordini (pips)
input int    InpSLFase1Point  = 500; // Stop loss di ogni ordine (point, 0 = nessuno)

input group "=== FASE 2 - Il range si allarga ==="
input double InpStepFase2Pips   = 150;  // Distanza minima tra gli ordini (pips)
input double InpDivisoreFase2   = 8;    // Se range/divisore e' piu' grande usa quello (0 = fissa)
input int    InpSLFase2Point    = 650;  // Stop loss di ogni ordine (point, 0 = nessuno)
input bool   InpNuoviCicliFase2 = true; // Aprire nuovi cicli anche in fase 2

input group "=== Martingala ==="
input double InpLottoIniziale       = 0.01; // Lotto del primo ordine
input double InpMoltiplicatore      = 2.0;  // Moltiplicatore del lotto a ogni nuovo ordine
input double InpLottoMassimo        = 1.0;  // Lotto massimo per ordine (0 = nessun limite)
input int    InpMaxOrdiniCiclo      = 8;    // Ordini massimi per ciclo
input double InpTPCicloPips         = 40;   // Obiettivo del ciclo (pips calcolati sul lotto iniziale)
input double InpMaxPerditaCicloPerc = 0;    // Chiudi tutto se il ciclo perde questa % del saldo (0 = off)

input group "=== Ingresso ==="
input ENUM_DIREZIONE InpDirezione        = DIR_AUTOMATICA; // Direzione degli ingressi
input double         InpZonaIngressoPerc = 30;             // BUY nel X% basso del range, SELL nel X% alto

input group "=== Varie ==="
input long   InpMagic         = 20261102; // Magic number
input int    InpSlippagePoint = 30;       // Slippage massimo (point)
input double InpPointPerPip   = 0;        // Point in 1 pip (0 = automatico: 10 su forex 5 cifre e oro)
input bool   InpPannello      = true;     // Mostra il pannello sul grafico

//+------------------------------------------------------------------+
//| Stato                                                            |
//+------------------------------------------------------------------+
CTrade   g_trade;
double   g_pipPoints          = 10;    // quanti point fanno 1 pip
bool     g_cicloAttivo        = false;
datetime g_inizioCiclo        = 0;
int      g_ordiniCiclo        = 0;     // ordini aperti nel ciclo (anche quelli già chiusi in SL)
int      g_dirCiclo           = -1;    // ORDER_TYPE_BUY / ORDER_TYPE_SELL dell'ultimo ordine
double   g_ultimoPrezzo       = 0;     // prezzo dell'ultimo ordine del ciclo
bool     g_aggiornaRealizzato = true;
double   g_realizzato         = 0;
datetime g_pausaFinoA         = 0;
int      g_nPosPrecedente     = -1;
int      g_ultimaFase         = 0;

//+------------------------------------------------------------------+
int OnInit()
  {
   if(InpBarreRange < 2 || InpStepFase1Pips <= 0 || InpStepFase2Pips <= 0 ||
      InpLottoIniziale <= 0 || InpMoltiplicatore < 1.0 || InpMaxOrdiniCiclo < 1 ||
      InpTPCicloPips <= 0 || InpRangeLateraleMaxPips >= InpRangeMaxPips ||
      InpZonaIngressoPerc <= 0 || InpZonaIngressoPerc > 100)
     {
      Print("Parametri non validi: controlla gli input dell'EA.");
      return INIT_PARAMETERS_INCORRECT;
     }

   if((ENUM_ACCOUNT_MARGIN_MODE)AccountInfoInteger(ACCOUNT_MARGIN_MODE) != ACCOUNT_MARGIN_MODE_RETAIL_HEDGING)
     {
      Print("Serve un conto HEDGING: su un conto netting gli ordini della griglia si fonderebbero in una sola posizione.");
      return INIT_FAILED;
     }

   g_pipPoints = CalcolaPointPerPip();
   g_trade.SetExpertMagicNumber((ulong)InpMagic);
   g_trade.SetDeviationInPoints((ulong)InpSlippagePoint);
   g_trade.SetTypeFillingBySymbol(_Symbol);

   RipristinaStato();

   double valorePip = ValorePipPerLotto();
   if(valorePip <= 0)
      Print("Attenzione: valore del pip non disponibile, l'obiettivo del ciclo non puo' essere calcolato.");
   PrintFormat("Martingala 3 fasi su %s: 1 pip = %.0f point, 1 pip con 1 lotto = %.2f %s, obiettivo ciclo = %.2f %s",
               _Symbol, g_pipPoints, valorePip, AccountInfoString(ACCOUNT_CURRENCY),
               ObiettivoCiclo(), AccountInfoString(ACCOUNT_CURRENCY));
   return INIT_SUCCEEDED;
  }

//+------------------------------------------------------------------+
void OnDeinit(const int reason)
  {
   Comment("");
  }

//+------------------------------------------------------------------+
void OnTrade()
  {
   g_aggiornaRealizzato = true;
  }

//+------------------------------------------------------------------+
void OnTick()
  {
   double rangePips = 0, massimo = 0, minimo = 0;
   if(!CalcolaRange(rangePips, massimo, minimo))
      return;

   ENUM_FASE fase = DeterminaFase(rangePips);
   if((int)fase != g_ultimaFase)
     {
      PrintFormat("%s - range %.1f pips", NomeFase(fase), rangePips);
      g_ultimaFase = (int)fase;
     }

   int      dir          = -1;
   double   prezzoUltimo = 0;
   double   fluttuante   = 0;
   datetime tPrimo       = 0;
   int nPos = LeggiPosizioni(dir, prezzoUltimo, tPrimo, fluttuante);
   if(nPos != g_nPosPrecedente)
     {
      g_aggiornaRealizzato = true; // si è aperta o chiusa una posizione (es. stop loss)
      g_nPosPrecedente = nPos;
     }

   //--- posizioni dell'EA senza un ciclo in memoria: le riprendo in gestione
   if(nPos > 0 && !g_cicloAttivo)
     {
      g_cicloAttivo        = true;
      g_inizioCiclo        = tPrimo;
      g_ordiniCiclo        = nPos;
      g_dirCiclo           = dir;
      g_ultimoPrezzo       = prezzoUltimo;
      g_aggiornaRealizzato = true;
      SalvaStato();
     }
   if(nPos > 0)
     {
      g_dirCiclo     = dir;
      g_ultimoPrezzo = prezzoUltimo;
     }

   double obiettivo = ObiettivoCiclo();
   double risultato = 0;

   if(g_cicloAttivo)
     {
      double realizzato = ProfittoRealizzato();
      risultato = realizzato + fluttuante;

      //--- obiettivo raggiunto: chiudo tutto e riparto da capo con il lotto iniziale
      if(obiettivo > 0 && risultato >= obiettivo)
        {
         if(nPos == 0 || ChiudiTutto())
            FineCiclo("obiettivo raggiunto", risultato);
         AggiornaPannello(fase, rangePips, risultato, obiettivo);
         return;
        }

      //--- protezione facoltativa: perdita massima del ciclo
      if(InpMaxPerditaCicloPerc > 0)
        {
         double saldoInizio = AccountInfoDouble(ACCOUNT_BALANCE) - realizzato;
         if(risultato <= -saldoInizio * InpMaxPerditaCicloPerc / 100.0)
           {
            if(nPos == 0 || ChiudiTutto())
               FineCiclo("perdita massima del ciclo", risultato);
            AggiornaPannello(fase, rangePips, risultato, obiettivo);
            return;
           }
        }

      //--- tutto chiuso in stop loss e il ciclo non può continuare
      if(nPos == 0 && (g_ordiniCiclo >= InpMaxOrdiniCiclo || fase == FASE_3_STOP))
        {
         FineCiclo(fase == FASE_3_STOP ? "fase 3, range oltre il limite" : "numero massimo di ordini raggiunto",
                   risultato);
         AggiornaPannello(fase, rangePips, risultato, obiettivo);
         return;
        }
     }

   //--- nuovi ordini: mai in fase 3
   if(fase != FASE_3_STOP && TimeCurrent() >= g_pausaFinoA)
     {
      if(g_cicloAttivo)
         GestisciGriglia(fase, rangePips, massimo, minimo, nPos);
      else
         if(fase == FASE_1_LATERALE || InpNuoviCicliFase2)
           {
            ENUM_ORDER_TYPE tipo = ORDER_TYPE_BUY;
            if(SegnaleIngresso(massimo, minimo, tipo))
               Apri(tipo, fase);
           }
     }

   AggiornaPannello(fase, rangePips, risultato, obiettivo);
  }

//+------------------------------------------------------------------+
//| Griglia: aggiunge ordini quando il prezzo va contro              |
//+------------------------------------------------------------------+
void GestisciGriglia(ENUM_FASE fase, double rangePips, double massimo, double minimo, int nPos)
  {
   if(g_ordiniCiclo >= InpMaxOrdiniCiclo)
      return;

   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t))
      return;

   ENUM_ORDER_TYPE tipo = ORDER_TYPE_BUY;

   //--- nessun riferimento valido (caso anomalo): riparto dal segnale
   if(g_dirCiclo < 0 || g_ultimoPrezzo <= 0)
     {
      if(nPos == 0 && SegnaleIngresso(massimo, minimo, tipo))
         Apri(tipo, fase);
      return;
     }

   double distanza = DistanzaPips(fase, rangePips) * g_pipPoints * _Point;

   //--- 1) il prezzo è andato contro l'ultimo ordine della distanza della fase:
   //       nuovo ordine nella stessa direzione con il lotto moltiplicato
   if(g_dirCiclo == ORDER_TYPE_BUY && t.ask <= g_ultimoPrezzo - distanza)
     {
      Apri(ORDER_TYPE_BUY, fase);
      return;
     }
   if(g_dirCiclo == ORDER_TYPE_SELL && t.bid >= g_ultimoPrezzo + distanza)
     {
      Apri(ORDER_TYPE_SELL, fase);
      return;
     }

   //--- 2) tutto chiuso in stop loss e il prezzo si è girato dall'altra parte del range:
   //       riparte nella direzione opposta, sempre con il lotto della martingala
   //       e sempre ad almeno "distanza" dall'ultimo ordine
   if(nPos == 0 && SegnaleIngresso(massimo, minimo, tipo) && (int)tipo != g_dirCiclo)
     {
      bool lontano = (tipo == ORDER_TYPE_BUY) ? (t.ask <= g_ultimoPrezzo - distanza)
                                               : (t.bid >= g_ultimoPrezzo + distanza);
      if(lontano)
         Apri(tipo, fase);
     }
  }

//+------------------------------------------------------------------+
//| Segnale di ingresso: BUY nella parte bassa del range, SELL alta  |
//+------------------------------------------------------------------+
bool SegnaleIngresso(double massimo, double minimo, ENUM_ORDER_TYPE &tipo)
  {
   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t))
      return false;

   double ampiezza = massimo - minimo;
   if(ampiezza <= 0)
      return false;

   double zona   = ampiezza * InpZonaIngressoPerc / 100.0;
   bool   compra = (t.bid <= minimo + zona) && InpDirezione != DIR_SOLO_SELL;
   bool   vendi  = (t.bid >= massimo - zona) && InpDirezione != DIR_SOLO_BUY;

   if(compra && vendi) // zona >= 50%: decide la metà del range
     {
      if(t.bid < (massimo + minimo) / 2.0)
         vendi = false;
      else
         compra = false;
     }

   if(compra)
     {
      tipo = ORDER_TYPE_BUY;
      return true;
     }
   if(vendi)
     {
      tipo = ORDER_TYPE_SELL;
      return true;
     }
   return false;
  }

//+------------------------------------------------------------------+
//| Apre un ordine della martingala                                  |
//+------------------------------------------------------------------+
bool Apri(ENUM_ORDER_TYPE tipo, ENUM_FASE fase)
  {
   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t))
      return false;

   double lotto  = LottoOrdine(g_ordiniCiclo);
   double prezzo = (tipo == ORDER_TYPE_BUY) ? t.ask : t.bid;

   //--- stop loss della fase, rispettando la distanza minima del broker
   int slPoint     = StopLossPoint(fase);
   int livelloStop = (int)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_STOPS_LEVEL);
   if(slPoint > 0 && slPoint <= livelloStop)
      slPoint = livelloStop + 1;
   double sl = 0;
   if(slPoint > 0)
      sl = NormalizeDouble(tipo == ORDER_TYPE_BUY ? prezzo - slPoint * _Point
                                                  : prezzo + slPoint * _Point, _Digits);

   double margine = 0;
   if(!OrderCalcMargin(tipo, _Symbol, lotto, prezzo, margine) ||
      margine > AccountInfoDouble(ACCOUNT_MARGIN_FREE))
     {
      PrintFormat("Margine insufficiente per %.2f lotti: ordine non aperto.", lotto);
      g_pausaFinoA = TimeCurrent() + 60;
      return false;
     }

   datetime inizio   = TimeCurrent();
   string   commento = StringFormat("M3F fase%d n%d", (int)fase, g_ordiniCiclo + 1);
   bool ok = (tipo == ORDER_TYPE_BUY) ? g_trade.Buy(lotto, _Symbol, prezzo, sl, 0, commento)
                                      : g_trade.Sell(lotto, _Symbol, prezzo, sl, 0, commento);
   uint rc = g_trade.ResultRetcode();
   if(!ok || (rc != TRADE_RETCODE_DONE && rc != TRADE_RETCODE_DONE_PARTIAL && rc != TRADE_RETCODE_PLACED))
     {
      PrintFormat("Errore apertura ordine: %u %s", rc, g_trade.ResultRetcodeDescription());
      g_pausaFinoA = TimeCurrent() + 10;
      return false;
     }

   if(!g_cicloAttivo)
     {
      g_cicloAttivo = true;
      g_inizioCiclo = inizio;
     }
   double eseguito = g_trade.ResultPrice();
   g_ultimoPrezzo       = (eseguito > 0) ? eseguito : prezzo;
   g_dirCiclo           = (int)tipo;
   g_ordiniCiclo++;
   g_aggiornaRealizzato = true;
   g_pausaFinoA         = TimeCurrent() + 1; // evita doppi ordini mentre la posizione si registra
   SalvaStato();

   PrintFormat("%s ordine %d/%d: %s %.2f lotti a %s, SL %s (%d point)",
               NomeFase(fase), g_ordiniCiclo, InpMaxOrdiniCiclo,
               tipo == ORDER_TYPE_BUY ? "BUY" : "SELL", lotto,
               DoubleToString(g_ultimoPrezzo, _Digits),
               sl > 0 ? DoubleToString(sl, _Digits) : "nessuno", slPoint);
   return true;
  }

//+------------------------------------------------------------------+
bool ChiudiTutto()
  {
   bool tutto = true;
   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0)
         continue;
      if(PositionGetInteger(POSITION_MAGIC) != InpMagic || PositionGetString(POSITION_SYMBOL) != _Symbol)
         continue;
      if(!g_trade.PositionClose(ticket))
        {
         tutto = false;
         PrintFormat("Errore chiusura posizione %s: %u %s", IntegerToString((long)ticket),
                     g_trade.ResultRetcode(), g_trade.ResultRetcodeDescription());
        }
     }
   g_aggiornaRealizzato = true;
   return tutto;
  }

//+------------------------------------------------------------------+
void FineCiclo(const string motivo, double risultato)
  {
   PrintFormat("Ciclo chiuso (%s): %d ordini, risultato %.2f %s",
               motivo, g_ordiniCiclo, risultato, AccountInfoString(ACCOUNT_CURRENCY));
   g_cicloAttivo        = false;
   g_inizioCiclo        = 0;
   g_ordiniCiclo        = 0;
   g_dirCiclo           = -1;
   g_ultimoPrezzo       = 0;
   g_realizzato         = 0;
   g_aggiornaRealizzato = true;
   g_pausaFinoA         = TimeCurrent() + 1;
   CancellaStato();
  }

//+------------------------------------------------------------------+
//| Range = massimo - minimo delle ultime N candele.                 |
//| Durante un ciclo conta anche tutto ciò che è successo dall'inizio|
//| del ciclo: così la fase può solo salire (1 -> 2 -> 3), mai tornare|
//| alla griglia stretta mentre si è in perdita.                     |
//+------------------------------------------------------------------+
bool CalcolaRange(double &rangePips, double &massimo, double &minimo)
  {
   int barre = InpBarreRange;
   if(g_cicloAttivo && g_inizioCiclo > 0)
     {
      int shift = iBarShift(_Symbol, InpTFRange, g_inizioCiclo, false);
      if(shift >= 0 && shift + InpBarreRange > barre)
         barre = shift + InpBarreRange;
     }

   int disponibili = iBars(_Symbol, InpTFRange);
   if(disponibili < InpBarreRange)
      return false;
   if(barre > disponibili)
      barre = disponibili;

   int iMax = iHighest(_Symbol, InpTFRange, MODE_HIGH, barre, 0);
   int iMin = iLowest(_Symbol, InpTFRange, MODE_LOW, barre, 0);
   if(iMax < 0 || iMin < 0)
      return false;

   massimo = iHigh(_Symbol, InpTFRange, iMax);
   minimo  = iLow(_Symbol, InpTFRange, iMin);
   if(massimo <= 0 || minimo <= 0 || massimo < minimo)
      return false;

   rangePips = (massimo - minimo) / _Point / g_pipPoints;
   return true;
  }

//+------------------------------------------------------------------+
ENUM_FASE DeterminaFase(double rangePips)
  {
   if(rangePips <= InpRangeLateraleMaxPips)
      return FASE_1_LATERALE;
   if(rangePips < InpRangeMaxPips)
      return FASE_2_ALLARGAMENTO;
   return FASE_3_STOP;
  }

//+------------------------------------------------------------------+
//| Distanza tra gli ordini (pips) nella fase corrente               |
//+------------------------------------------------------------------+
double DistanzaPips(ENUM_FASE fase, double rangePips)
  {
   if(fase == FASE_1_LATERALE)
      return InpStepFase1Pips;
   double d = InpStepFase2Pips;
   if(InpDivisoreFase2 > 0 && rangePips / InpDivisoreFase2 > d)
      d = rangePips / InpDivisoreFase2; // es. range 2000 pips / 8 = 250 pips
   return d;
  }

//+------------------------------------------------------------------+
int StopLossPoint(ENUM_FASE fase)
  {
   return (fase == FASE_1_LATERALE) ? InpSLFase1Point : InpSLFase2Point;
  }

//+------------------------------------------------------------------+
string NomeFase(ENUM_FASE fase)
  {
   if(fase == FASE_1_LATERALE)
      return "FASE 1 laterale";
   if(fase == FASE_2_ALLARGAMENTO)
      return "FASE 2 allargamento";
   return "FASE 3 stop";
  }

//+------------------------------------------------------------------+
//| Posizioni dell'EA: numero, direzione e prezzo dell'ultima,       |
//| apertura della prima, profitto fluttuante                        |
//+------------------------------------------------------------------+
int LeggiPosizioni(int &dir, double &prezzoUltimo, datetime &tPrimo, double &fluttuante)
  {
   int  n    = 0;
   long tMax = -1;
   dir          = -1;
   prezzoUltimo = 0;
   tPrimo       = 0;
   fluttuante   = 0;
   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0)
         continue;
      if(PositionGetInteger(POSITION_MAGIC) != InpMagic || PositionGetString(POSITION_SYMBOL) != _Symbol)
         continue;
      n++;
      fluttuante += PositionGetDouble(POSITION_PROFIT) + PositionGetDouble(POSITION_SWAP);
      datetime t = (datetime)PositionGetInteger(POSITION_TIME);
      if(tPrimo == 0 || t < tPrimo)
         tPrimo = t;
      long tms = PositionGetInteger(POSITION_TIME_MSC);
      if(tms > tMax)
        {
         tMax         = tms;
         dir          = (int)PositionGetInteger(POSITION_TYPE);
         prezzoUltimo = PositionGetDouble(POSITION_PRICE_OPEN);
        }
     }
   return n;
  }

//+------------------------------------------------------------------+
//| Profitto già chiuso nel ciclo (stop loss inclusi), con           |
//| commissioni e swap                                               |
//+------------------------------------------------------------------+
double ProfittoRealizzato()
  {
   if(!g_cicloAttivo)
      return 0;
   if(!g_aggiornaRealizzato)
      return g_realizzato;
   if(!HistorySelect(g_inizioCiclo, TimeCurrent() + 86400))
      return g_realizzato;

   //--- posizioni aperte dall'EA in questo ciclo
   ulong ids[];
   int   nIds   = 0;
   int   totale = HistoryDealsTotal();
   for(int i = 0; i < totale; i++)
     {
      ulong deal = HistoryDealGetTicket(i);
      if(deal == 0)
         continue;
      if(HistoryDealGetInteger(deal, DEAL_MAGIC) != InpMagic)
         continue;
      if(HistoryDealGetString(deal, DEAL_SYMBOL) != _Symbol)
         continue;
      if(HistoryDealGetInteger(deal, DEAL_ENTRY) != DEAL_ENTRY_IN)
         continue;
      if((datetime)HistoryDealGetInteger(deal, DEAL_TIME) < g_inizioCiclo)
         continue;
      ArrayResize(ids, nIds + 1);
      ids[nIds] = (ulong)HistoryDealGetInteger(deal, DEAL_POSITION_ID);
      nIds++;
     }

   //--- somma di tutti i deal di quelle posizioni (entrate e uscite)
   double somma = 0;
   for(int i = 0; i < totale; i++)
     {
      ulong deal = HistoryDealGetTicket(i);
      if(deal == 0)
         continue;
      ulong posizione = (ulong)HistoryDealGetInteger(deal, DEAL_POSITION_ID);
      bool  nostra    = false;
      for(int k = 0; k < nIds; k++)
        {
         if(ids[k] == posizione)
           {
            nostra = true;
            break;
           }
        }
      if(!nostra)
         continue;
      somma += HistoryDealGetDouble(deal, DEAL_PROFIT) + HistoryDealGetDouble(deal, DEAL_SWAP) +
               HistoryDealGetDouble(deal, DEAL_COMMISSION) + HistoryDealGetDouble(deal, DEAL_FEE);
     }

   g_realizzato         = somma;
   g_aggiornaRealizzato = false;
   return somma;
  }

//+------------------------------------------------------------------+
//| Lotti                                                            |
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
//| Lotto dell'ordine numero "indice" del ciclo (0 = primo)          |
//+------------------------------------------------------------------+
double LottoOrdine(int indice)
  {
   return NormalizzaLotto(InpLottoIniziale * MathPow(InpMoltiplicatore, indice));
  }

//+------------------------------------------------------------------+
//| Pip e obiettivo                                                  |
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
   return g_pipPoints * _Point / dimTick * valoreTick;
  }

//+------------------------------------------------------------------+
//| Il ciclo si chiude quando (perdite già chiuse + posizioni aperte)|
//| guadagna questi pips calcolati sul lotto iniziale                |
//+------------------------------------------------------------------+
double ObiettivoCiclo()
  {
   return InpTPCicloPips * ValorePipPerLotto() * NormalizzaLotto(InpLottoIniziale);
  }

//+------------------------------------------------------------------+
//| Stato salvato nelle variabili globali del terminale, così un     |
//| riavvio di MT5 non fa ripartire la martingala dal lotto iniziale |
//+------------------------------------------------------------------+
string NomeGV(const string chiave)
  {
   return "M3F_" + IntegerToString(InpMagic) + "_" + _Symbol + "_" + chiave;
  }

//+------------------------------------------------------------------+
void SalvaStato()
  {
   if(MQLInfoInteger(MQL_TESTER))
      return;
   GlobalVariableSet(NomeGV("inizio"), (double)g_inizioCiclo);
   GlobalVariableSet(NomeGV("ordini"), g_ordiniCiclo);
   GlobalVariableSet(NomeGV("dir"), g_dirCiclo);
   GlobalVariableSet(NomeGV("prezzo"), g_ultimoPrezzo);
  }

//+------------------------------------------------------------------+
void CancellaStato()
  {
   if(MQLInfoInteger(MQL_TESTER))
      return;
   GlobalVariableDel(NomeGV("inizio"));
   GlobalVariableDel(NomeGV("ordini"));
   GlobalVariableDel(NomeGV("dir"));
   GlobalVariableDel(NomeGV("prezzo"));
  }

//+------------------------------------------------------------------+
void RipristinaStato()
  {
   int      dir          = -1;
   double   prezzoUltimo = 0;
   double   fluttuante   = 0;
   datetime tPrimo       = 0;
   int nPos = LeggiPosizioni(dir, prezzoUltimo, tPrimo, fluttuante);

   if(!MQLInfoInteger(MQL_TESTER) && GlobalVariableCheck(NomeGV("inizio")))
     {
      g_cicloAttivo  = true;
      g_inizioCiclo  = (datetime)(long)GlobalVariableGet(NomeGV("inizio"));
      g_ordiniCiclo  = (int)GlobalVariableGet(NomeGV("ordini"));
      g_dirCiclo     = (int)GlobalVariableGet(NomeGV("dir"));
      g_ultimoPrezzo = GlobalVariableGet(NomeGV("prezzo"));
      if(nPos > 0)
        {
         g_dirCiclo     = dir;
         g_ultimoPrezzo = prezzoUltimo;
         if(nPos > g_ordiniCiclo)
            g_ordiniCiclo = nPos;
        }
      PrintFormat("Ciclo ripristinato: %d ordini, ultimo a %s", g_ordiniCiclo, DoubleToString(g_ultimoPrezzo, _Digits));
     }
   else
      if(nPos > 0)
        {
         g_cicloAttivo  = true;
         g_inizioCiclo  = tPrimo;
         g_ordiniCiclo  = nPos;
         g_dirCiclo     = dir;
         g_ultimoPrezzo = prezzoUltimo;
         SalvaStato();
         PrintFormat("Riprese in gestione %d posizioni aperte", nPos);
        }
   g_aggiornaRealizzato = true;
  }

//+------------------------------------------------------------------+
void AggiornaPannello(ENUM_FASE fase, double rangePips, double risultato, double obiettivo)
  {
   if(!InpPannello)
      return;
   if(MQLInfoInteger(MQL_TESTER) && !MQLInfoInteger(MQL_VISUAL_MODE))
      return;

   string valuta = AccountInfoString(ACCOUNT_CURRENCY);
   string testo  = "MARTINGALA 3 FASI\n";
   testo += StringFormat("%s  |  range %.1f pips (%d candele %s)\n",
                         NomeFase(fase), rangePips, InpBarreRange, StringSubstr(EnumToString(InpTFRange), 7));
   if(fase == FASE_3_STOP)
      testo += "Nessun nuovo ordine: il range ha superato il limite\n";
   else
      testo += StringFormat("Distanza ordini %.1f pips  |  SL %d point\n",
                            DistanzaPips(fase, rangePips), StopLossPoint(fase));
   if(g_cicloAttivo)
     {
      testo += StringFormat("Ciclo: %d/%d ordini  |  prossimo lotto %.2f\n",
                            g_ordiniCiclo, InpMaxOrdiniCiclo, LottoOrdine(g_ordiniCiclo));
      testo += StringFormat("Risultato ciclo %.2f / obiettivo %.2f %s\n", risultato, obiettivo, valuta);
     }
   else
      testo += "Nessun ciclo aperto: in attesa di ingresso\n";
   Comment(testo);
  }
//+------------------------------------------------------------------+
