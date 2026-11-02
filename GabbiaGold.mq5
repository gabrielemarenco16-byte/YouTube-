//+------------------------------------------------------------------+
//|                                                   GabbiaGold.mq5 |
//|   Expert Advisor a griglia/gabbia veloce per XAUUSD (oro)        |
//|   - Trend con due EMA sulla candela chiusa                       |
//|   - Gabbia di posizioni principali nel verso del trend           |
//|   - Coperture a TP corto sui ritracciamenti                      |
//|   - Lotto che cresce con il saldo (compounding a gradini)        |
//|   - Gestione del rischio: SL, perdita giornaliera, drawdown max  |
//|                                                                  |
//|   RICHIEDE UN CONTO HEDGING (posizioni opposte sullo stesso      |
//|   simbolo). I conti MT5 Vantage standard sono hedging.           |
//+------------------------------------------------------------------+
#property copyright   "GabbiaGold"
#property version     "1.00"
#property description "Gabbia veloce su XAUUSD con coperture sui ritracciamenti e lotto in base al saldo."
#property description "Usare SEMPRE prima su conto demo. Il trading con leva comporta un alto rischio di perdita."

#include <Trade\Trade.mqh>

//+------------------------------------------------------------------+
//| PARAMETRI DI INPUT                                               |
//+------------------------------------------------------------------+
input group "=== Trend (EMA) ==="
input ENUM_TIMEFRAMES InpTimeframe = PERIOD_M1; // Timeframe su cui calcolare le EMA
input int             InpEMAVeloce = 9;         // Periodo EMA veloce
input int             InpEMALenta  = 21;        // Periodo EMA lenta

input group "=== Gabbia / Griglia ==="
input double InpPasso         = 1.5;  // Passo della gabbia in USD di prezzo (es. 1.5 = 150 punti se digits=2)
input int    InpMaxPrincipali = 3;    // Numero massimo di posizioni principali nel verso del trend
input double InpTP_Principale = 0.0;  // Take profit delle principali in USD di prezzo (0 = nessuno)
input double InpTP_Copertura  = 1.0;  // Take profit delle coperture in USD di prezzo
input int    InpMaxCoperture  = 1;    // Numero massimo di coperture aperte contemporaneamente
input int    InpCooldownSec   = 5;    // Pausa minima in secondi tra un'apertura e la successiva
input double InpBasketTP_EUR      = 0.0;  // Basket TP: profitto netto totale in valuta del conto (EUR) per chiudere tutto (0 = disattivato)

input group "=== Commissioni ==="
input double InpCommissioneLottoRT = 6.0;   // Commissione andata+ritorno per 1 lotto, in valuta del conto (usata per il profitto netto)
input bool   InpSimulaCommTester   = false; // Solo Strategy Tester: scala la commissione dal saldo se il tester non la applica

input group "=== Lotto in base al saldo (compounding) ==="
input double InpLottoBase       = 0.01;  // Lotto base (minimo)
input double InpSaldoPerGradino = 250.0; // Saldo (valuta conto) per ogni gradino di lotto base
input double InpLottoMax        = 1.00;  // Lotto massimo consentito

input group "=== Gestione del rischio ==="
input double InpSL_Posizione     = 5.0;   // Stop loss per ogni posizione in USD di prezzo (obbligatorio, > 0)
input double InpPerditaMaxGiorno = 5.0;   // Perdita massima giornaliera in % del saldo di inizio giornata (0 = off)
input double InpDrawdownMax      = 30.0;  // Drawdown massimo in % dal picco di equity: ferma il bot (0 = off)
input double InpSpreadMax        = 0.50;  // Spread massimo in USD di prezzo per aprire nuove posizioni
input bool   InpResetBloccoDD    = false; // true = sblocca il blocco da drawdown al riavvio (poi rimettere false)

input group "=== Filtro orario (ora del SERVER) ==="
input bool InpUsaFiltroOrario    = true; // Attiva il filtro orario
input int  InpOraInizio          = 1;    // Ora di inizio trading (0-23)
input int  InpMinutoInizio       = 30;   // Minuto di inizio trading (0-59)
input int  InpOraFine            = 23;   // Ora di fine trading (0-23)
input int  InpMinutoFine         = 0;    // Minuto di fine trading (0-59)
input int  InpMargineSessioneMin = 15;   // Minuti da evitare dopo l'apertura e prima della chiusura della sessione / rollover (0 = off)

input group "=== Esecuzione ==="
input long   InpMagic         = 20261102;     // Magic number delle principali (le coperture usano Magic+1)
input int    InpSlippagePunti = 30;           // Slippage massimo in punti
input int    InpMaxTentativi  = 3;            // Tentativi massimi per ordine in caso di requote/errori
input string InpCommento      = "GabbiaGold"; // Commento degli ordini

//+------------------------------------------------------------------+
//| STRUTTURA CON LO STATO DELLE POSIZIONI DEL BOT                   |
//+------------------------------------------------------------------+
struct StatoPosizioni
  {
   int                nPrinc;            // numero principali
   int                nCop;              // numero coperture
   int                nPrincBuy;         // principali BUY
   int                nPrincSell;        // principali SELL
   int                nCopBuy;           // coperture BUY
   int                nCopSell;          // coperture SELL
   double             prezzoUltimaPrinc; // prezzo di apertura dell'ultima principale
   long               tempoUltimaPrinc;  // orario (ms) dell'ultima principale
   double             volumePrinc;       // volume dell'ultima principale
   ENUM_POSITION_TYPE tipoUltimaPrinc;   // tipo dell'ultima principale
   double             profittoNetto;     // profitto + swap - commissioni stimate
  };

//+------------------------------------------------------------------+
//| VARIABILI GLOBALI                                                |
//+------------------------------------------------------------------+
CTrade   g_trade;
int      g_hEMAVeloce       = INVALID_HANDLE;
int      g_hEMALenta        = INVALID_HANDLE;
long     g_magicPrinc       = 0;
long     g_magicCop         = 0;
int      g_direzione        = 0;      // +1 = BUY, -1 = SELL, 0 = indefinita
int      g_trend            = 0;      // trend letto dalle EMA (+1 / -1 / 0)
datetime g_ultimaBarra      = 0;      // ultima barra su cui è stato letto il trend
double   g_lottoCiclo       = 0.0;    // lotto del ciclo corrente
datetime g_ultimaApertura   = 0;      // orario dell'ultimo tentativo di apertura (cooldown)
double   g_saldoInizioGiorno= 0.0;    // saldo a inizio giornata
datetime g_giornoCorrente   = 0;      // inizio del giorno corrente (ora server)
datetime g_giornoBlocco     = 0;      // giorno in cui è scattato il blocco giornaliero
double   g_piccoEquity      = 0.0;    // picco massimo di equity
bool     g_bloccoDD         = false;  // blocco permanente per drawdown
int      g_digits           = 2;
double   g_point            = 0.01;
double   g_tickSize         = 0.01;
string   g_prefGV           = "";     // prefisso delle GlobalVariable
string   g_statoFiltri      = "";     // testo per il pannello
ENUM_ORDER_TYPE_FILLING g_filling = ORDER_FILLING_FOK; // modalità di riempimento in uso

//+------------------------------------------------------------------+
//| ESECUZIONE: modalità di riempimento                              |
//|  Usa SetTypeFillingBySymbol; se il broker la rifiuta             |
//|  (INVALID_FILL) prova la successiva: FOK -> IOC -> RETURN        |
//+------------------------------------------------------------------+
void ImpostaFilling()
  {
   long flag = SymbolInfoInteger(_Symbol, SYMBOL_FILLING_MODE);
   if(g_trade.SetTypeFillingBySymbol(_Symbol))
     {
      if((flag & SYMBOL_FILLING_FOK) == SYMBOL_FILLING_FOK)
         g_filling = ORDER_FILLING_FOK;
      else
         g_filling = ORDER_FILLING_IOC;
     }
   else
     {
      g_filling = ORDER_FILLING_RETURN;
      g_trade.SetTypeFilling(g_filling);
     }
   PrintFormat("GabbiaGold: modalità di riempimento %s", EnumToString(g_filling));
  }

void CambiaFilling()
  {
   if(g_filling == ORDER_FILLING_FOK)
      g_filling = ORDER_FILLING_IOC;
   else
      if(g_filling == ORDER_FILLING_IOC)
         g_filling = ORDER_FILLING_RETURN;
      else
         g_filling = ORDER_FILLING_FOK;
   g_trade.SetTypeFilling(g_filling);
   PrintFormat("GabbiaGold: riempimento rifiutato dal broker, passo a %s", EnumToString(g_filling));
  }

//+------------------------------------------------------------------+
//| UTILITÀ: GlobalVariable con prefisso                             |
//+------------------------------------------------------------------+
double GVGet(const string nome, const double predefinito)
  {
   string n = g_prefGV + nome;
   if(GlobalVariableCheck(n))
      return GlobalVariableGet(n);
   return predefinito;
  }

void GVSet(const string nome, const double valore)
  {
   if(GlobalVariableSet(g_prefGV + nome, valore) == 0)
      PrintFormat("GabbiaGold: impossibile salvare la GlobalVariable %s (errore %d)", g_prefGV + nome, GetLastError());
  }

//+------------------------------------------------------------------+
//| UTILITÀ: log limitato (evita di riempire il diario)              |
//+------------------------------------------------------------------+
void LogLimitato(const string chiave, const string messaggio)
  {
   static string   ultimaChiave = "";
   static datetime ultimoLog    = 0;
   if(chiave != ultimaChiave || (long)TimeCurrent() - (long)ultimoLog >= 60)
     {
      Print(messaggio);
      ultimaChiave = chiave;
      ultimoLog    = TimeCurrent();
     }
  }

//+------------------------------------------------------------------+
//| UTILITÀ: inizio del giorno (ora server)                          |
//+------------------------------------------------------------------+
datetime InizioGiorno(const datetime t)
  {
   long s = (long)t;
   return (datetime)(s - s % 86400);
  }

//+------------------------------------------------------------------+
//| UTILITÀ: normalizza un prezzo a tick size e digits               |
//+------------------------------------------------------------------+
double NormPrezzo(double prezzo)
  {
   if(g_tickSize > 0.0)
      prezzo = MathRound(prezzo / g_tickSize) * g_tickSize;
   return NormalizeDouble(prezzo, g_digits);
  }

//+------------------------------------------------------------------+
//| LOTTO: normalizza con min / max / step del simbolo               |
//+------------------------------------------------------------------+
double NormalizzaLotto(double lotto)
  {
   double vmin = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MIN);
   double vmax = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_MAX);
   double step = SymbolInfoDouble(_Symbol, SYMBOL_VOLUME_STEP);
   if(step <= 0.0)
      step = 0.01;
   lotto = MathFloor(lotto / step + 1e-8) * step;
   if(lotto < vmin)
      lotto = vmin;
   if(vmax > 0.0 && lotto > vmax)
      lotto = vmax;
//--- numero di decimali dello step (es. 0.01 -> 2)
   int    decimali = 0;
   double s        = step;
   while(s < 1.0 - 1e-10 && decimali < 8)
     {
      s *= 10.0;
      decimali++;
     }
   return NormalizeDouble(lotto, decimali);
  }

//+------------------------------------------------------------------+
//| LOTTO: LottoBase x floor(Saldo / SaldoPerGradino), min LottoBase |
//+------------------------------------------------------------------+
double CalcolaLotto()
  {
   double saldo   = AccountInfoDouble(ACCOUNT_BALANCE);
   double gradini = 1.0;
   if(InpSaldoPerGradino > 0.0)
      gradini = MathFloor(saldo / InpSaldoPerGradino);
   if(gradini < 1.0)
      gradini = 1.0;
   double lotto = InpLottoBase * gradini;
   if(lotto > InpLottoMax)
      lotto = InpLottoMax;
   return NormalizzaLotto(lotto);
  }

//+------------------------------------------------------------------+
//| POSIZIONI: scansione filtrata per simbolo e magic                |
//+------------------------------------------------------------------+
void ScanPosizioni(StatoPosizioni &s)
  {
   ZeroMemory(s);
   s.tipoUltimaPrinc = POSITION_TYPE_BUY;
   for(int i = PositionsTotal() - 1; i >= 0; i--)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0)
         continue;
      if(PositionGetString(POSITION_SYMBOL) != _Symbol)
         continue;
      long magic = PositionGetInteger(POSITION_MAGIC);
      if(magic != g_magicPrinc && magic != g_magicCop)
         continue;

      double vol = PositionGetDouble(POSITION_VOLUME);
      ENUM_POSITION_TYPE tipo = (ENUM_POSITION_TYPE)PositionGetInteger(POSITION_TYPE);
      // profitto netto stimato se chiudessimo adesso (commissione completa andata+ritorno)
      s.profittoNetto += PositionGetDouble(POSITION_PROFIT)
                         + PositionGetDouble(POSITION_SWAP)
                         - InpCommissioneLottoRT * vol;

      if(magic == g_magicPrinc)
        {
         s.nPrinc++;
         if(tipo == POSITION_TYPE_BUY)
            s.nPrincBuy++;
         else
            s.nPrincSell++;
         long tempo = PositionGetInteger(POSITION_TIME_MSC);
         if(tempo > s.tempoUltimaPrinc)
           {
            s.tempoUltimaPrinc  = tempo;
            s.prezzoUltimaPrinc = PositionGetDouble(POSITION_PRICE_OPEN);
            s.volumePrinc       = vol;
            s.tipoUltimaPrinc   = tipo;
           }
        }
      else
        {
         s.nCop++;
         if(tipo == POSITION_TYPE_BUY)
            s.nCopBuy++;
         else
            s.nCopSell++;
        }
     }
  }

//+------------------------------------------------------------------+
//| ESECUZIONE: errori per cui ha senso ritentare                    |
//+------------------------------------------------------------------+
bool ErroreRitentabile(const uint rc)
  {
   switch(rc)
     {
      case TRADE_RETCODE_REQUOTE:
      case TRADE_RETCODE_PRICE_CHANGED:
      case TRADE_RETCODE_PRICE_OFF:
      case TRADE_RETCODE_CONNECTION:
      case TRADE_RETCODE_TIMEOUT:
      case TRADE_RETCODE_TOO_MANY_REQUESTS:
      case TRADE_RETCODE_ERROR:
      case TRADE_RETCODE_LOCKED:
         return true;
     }
   return false;
  }

//+------------------------------------------------------------------+
//| ESECUZIONE: distanza minima degli stop imposta dal broker        |
//+------------------------------------------------------------------+
double DistanzaMinStop(const MqlTick &tick)
  {
   long livello = SymbolInfoInteger(_Symbol, SYMBOL_TRADE_STOPS_LEVEL);
   // lo SL di un buy si confronta con il Bid: serve anche lo spread
   return (double)livello * g_point + (tick.ask - tick.bid) + g_point;
  }

//+------------------------------------------------------------------+
//| ESECUZIONE: apre una posizione con controllo margine e retry     |
//|  BUY ad Ask, SELL a Bid. SL/TP espressi come distanza in USD.    |
//+------------------------------------------------------------------+
bool ApriPosizione(const ENUM_ORDER_TYPE tipo, const double lotto,
                   const double distSL, const double distTP,
                   const long magic, const string etichetta)
  {
   g_ultimaApertura = TimeCurrent();   // il cooldown parte anche se l'ordine fallisce
   int cambiFilling = 0;

   for(int tentativo = 1; tentativo <= InpMaxTentativi; tentativo++)
     {
      MqlTick tick;
      if(!SymbolInfoTick(_Symbol, tick))
        {
         PrintFormat("GabbiaGold: prezzi non disponibili (tentativo %d/%d)", tentativo, InpMaxTentativi);
         Sleep(200);
         continue;
        }
      double prezzo = (tipo == ORDER_TYPE_BUY) ? tick.ask : tick.bid;

      //--- controllo del margine libero
      double margine = 0.0;
      if(!OrderCalcMargin(tipo, _Symbol, lotto, prezzo, margine))
        {
         PrintFormat("GabbiaGold: OrderCalcMargin fallito (errore %d) - ordine %s non inviato", GetLastError(), etichetta);
         return false;
        }
      double libero = AccountInfoDouble(ACCOUNT_MARGIN_FREE);
      if(margine > libero)
        {
         LogLimitato("margine",
                     StringFormat("GabbiaGold: MARGINE INSUFFICIENTE per %s %.2f lotti: servono %.2f, liberi %.2f - ordine non inviato",
                                  etichetta, lotto, margine, libero));
         return false;
        }

      //--- calcolo SL e TP rispettando la distanza minima del broker
      double minStop = DistanzaMinStop(tick);
      double dSL     = distSL;
      double dTP     = distTP;
      if(dSL > 0.0 && dSL < minStop)
         dSL = minStop;
      if(dTP > 0.0 && dTP < minStop)
         dTP = minStop;

      double sl = 0.0, tp = 0.0;
      if(tipo == ORDER_TYPE_BUY)
        {
         if(dSL > 0.0) sl = NormPrezzo(prezzo - dSL);
         if(dTP > 0.0) tp = NormPrezzo(prezzo + dTP);
        }
      else
        {
         if(dSL > 0.0) sl = NormPrezzo(prezzo + dSL);
         if(dTP > 0.0) tp = NormPrezzo(prezzo - dTP);
        }

      g_trade.SetExpertMagicNumber((ulong)magic);
      string commento = InpCommento + " " + etichetta;
      bool   inviato  = false;
      if(tipo == ORDER_TYPE_BUY)
         inviato = g_trade.Buy(lotto, _Symbol, prezzo, sl, tp, commento);
      else
         inviato = g_trade.Sell(lotto, _Symbol, prezzo, sl, tp, commento);

      uint rc = g_trade.ResultRetcode();
      if(inviato && (rc == TRADE_RETCODE_DONE || rc == TRADE_RETCODE_DONE_PARTIAL || rc == TRADE_RETCODE_PLACED))
        {
         PrintFormat("GabbiaGold: aperta %s %s %.2f lotti a %s  SL=%s TP=%s",
                     etichetta, (tipo == ORDER_TYPE_BUY ? "BUY" : "SELL"), lotto,
                     DoubleToString(g_trade.ResultPrice(), g_digits),
                     DoubleToString(sl, g_digits), DoubleToString(tp, g_digits));
         g_ultimaApertura = TimeCurrent();
         return true;
        }

      PrintFormat("GabbiaGold: apertura %s fallita (tentativo %d/%d) - retcode %u: %s",
                  etichetta, tentativo, InpMaxTentativi, rc, g_trade.ResultRetcodeDescription());
      if(rc == TRADE_RETCODE_INVALID_FILL && cambiFilling < 2)
        {
         CambiaFilling();
         cambiFilling++;
         tentativo--;                       // il cambio di riempimento non consuma un tentativo
         continue;
        }
      if(!ErroreRitentabile(rc))
         return false;
      Sleep(300);
     }
   PrintFormat("GabbiaGold: apertura %s abbandonata dopo %d tentativi", etichetta, InpMaxTentativi);
   return false;
  }

//+------------------------------------------------------------------+
//| ESECUZIONE: chiude una posizione con retry                       |
//+------------------------------------------------------------------+
bool ChiudiPosizione(const ulong ticket)
  {
   int cambiFilling = 0;
   for(int tentativo = 1; tentativo <= InpMaxTentativi; tentativo++)
     {
      if(!PositionSelectByTicket(ticket))
         return true;                       // già chiusa (es. SL/TP)
      if(g_trade.PositionClose(ticket, (ulong)InpSlippagePunti))
        {
         uint rc = g_trade.ResultRetcode();
         if(rc == TRADE_RETCODE_DONE)
            return true;
         if(rc == TRADE_RETCODE_DONE_PARTIAL)
            continue;                       // chiusura parziale: riprova sul resto
        }
      uint err = g_trade.ResultRetcode();
      PrintFormat("GabbiaGold: chiusura ticket %I64u fallita (tentativo %d/%d) - retcode %u: %s",
                  ticket, tentativo, InpMaxTentativi, err, g_trade.ResultRetcodeDescription());
      if(err == TRADE_RETCODE_INVALID_FILL && cambiFilling < 2)
        {
         CambiaFilling();
         cambiFilling++;
         tentativo--;
         continue;
        }
      if(!ErroreRitentabile(err))
         return false;
      Sleep(300);
     }
   return !PositionSelectByTicket(ticket);
  }

//+------------------------------------------------------------------+
//| ESECUZIONE: chiude TUTTE le posizioni del bot (simbolo + magic)  |
//+------------------------------------------------------------------+
bool ChiudiTutto(const string motivo)
  {
   PrintFormat("GabbiaGold: chiusura di tutte le posizioni - motivo: %s", motivo);
   for(int passata = 0; passata < 3; passata++)
     {
      ulong tickets[];
      int   n = 0;
      for(int i = PositionsTotal() - 1; i >= 0; i--)
        {
         ulong ticket = PositionGetTicket(i);
         if(ticket == 0)
            continue;
         if(PositionGetString(POSITION_SYMBOL) != _Symbol)
            continue;
         long magic = PositionGetInteger(POSITION_MAGIC);
         if(magic != g_magicPrinc && magic != g_magicCop)
            continue;
         if(ArrayResize(tickets, n + 1) != n + 1)
            break;
         tickets[n] = ticket;
         n++;
        }
      if(n == 0)
         return true;
      for(int k = 0; k < n; k++)
         ChiudiPosizione(tickets[k]);
     }
   StatoPosizioni s;
   ScanPosizioni(s);
   if(s.nPrinc + s.nCop > 0)
     {
      PrintFormat("GabbiaGold: ATTENZIONE - restano %d posizioni aperte, ritento al prossimo tick", s.nPrinc + s.nCop);
      return false;
     }
   return true;
  }

//+------------------------------------------------------------------+
//| TREND: legge le EMA sulla candela chiusa (indice 1)              |
//+------------------------------------------------------------------+
bool LeggiTrend(int &trend)
  {
   double veloce[], lenta[];
   if(CopyBuffer(g_hEMAVeloce, 0, 1, 1, veloce) != 1)
      return false;
   if(CopyBuffer(g_hEMALenta, 0, 1, 1, lenta) != 1)
      return false;
   if(veloce[0] > lenta[0])
      trend = 1;
   else
      if(veloce[0] < lenta[0])
         trend = -1;
      else
         trend = 0;
   return true;
  }

void AggiornaTrend()
  {
   datetime barra = iTime(_Symbol, InpTimeframe, 0);
   if(barra == 0)
      return;
   if(barra == g_ultimaBarra && g_trend != 0)
      return;                               // stessa barra: niente da ricalcolare
   int t = 0;
   if(LeggiTrend(t))
     {
      g_ultimaBarra = barra;
      if(t != 0)
         g_trend = t;                       // EMA uguali: si mantiene il trend precedente
     }
  }

//+------------------------------------------------------------------+
//| FILTRI: orario di trading (ora server)                           |
//+------------------------------------------------------------------+
bool DentroOrario()
  {
   if(!InpUsaFiltroOrario)
      return true;
   MqlDateTime dt;
   if(!TimeToStruct(TimeCurrent(), dt))
      return false;
   int adesso = dt.hour * 60 + dt.min;
   int inizio = InpOraInizio * 60 + InpMinutoInizio;
   int fine   = InpOraFine * 60 + InpMinutoFine;
   if(inizio == fine)
      return true;
   if(inizio < fine)
      return (adesso >= inizio && adesso < fine);
   return (adesso >= inizio || adesso < fine);   // finestra a cavallo della mezzanotte
  }

//+------------------------------------------------------------------+
//| FILTRI: evita i primi/ultimi minuti di ogni sessione (rollover)  |
//+------------------------------------------------------------------+
bool LontanoDaiBordiSessione()
  {
   if(InpMargineSessioneMin <= 0)
      return true;
   MqlDateTime dt;
   if(!TimeToStruct(TimeCurrent(), dt))
      return false;
   int adesso  = dt.hour * 3600 + dt.min * 60 + dt.sec;
   int margine = InpMargineSessioneMin * 60;
   bool trovata = false;
   for(uint i = 0; i < 10; i++)
     {
      datetime da = 0, a = 0;
      if(!SymbolInfoSessionTrade(_Symbol, (ENUM_DAY_OF_WEEK)dt.day_of_week, i, da, a))
         break;
      trovata = true;
      int sDa = (int)((long)da % 86400);
      int sA  = (int)(long)a;
      if(sA > 86400 || sA <= sDa)
         sA = 86400;                        // fine sessione "24:00"
      if(adesso >= sDa && adesso < sA)
        {
         if(adesso - sDa < margine)
            return false;                   // appena aperta la sessione
         if(sA - adesso < margine)
            return false;                   // sta per chiudere / rollover
         return true;
        }
     }
   if(!trovata)
      return true;                          // nessuna informazione sulle sessioni: non blocca
   return false;                            // fuori da tutte le sessioni
  }

//+------------------------------------------------------------------+
//| FILTRI: tutte le condizioni per poter aprire nuove posizioni     |
//+------------------------------------------------------------------+
bool PossoAprire(string &motivo)
  {
   if(!TerminalInfoInteger(TERMINAL_TRADE_ALLOWED) || !MQLInfoInteger(MQL_TRADE_ALLOWED))
     {
      motivo = "AutoTrading disattivato";
      return false;
     }
   ENUM_SYMBOL_TRADE_MODE modo = (ENUM_SYMBOL_TRADE_MODE)SymbolInfoInteger(_Symbol, SYMBOL_TRADE_MODE);
   if(modo != SYMBOL_TRADE_MODE_FULL)
     {
      motivo = "Simbolo non negoziabile ora";
      return false;
     }
   MqlTick tick;
   if(!SymbolInfoTick(_Symbol, tick))
     {
      motivo = "Prezzi non disponibili";
      return false;
     }
   double spread = tick.ask - tick.bid;
   if(spread > InpSpreadMax)
     {
      motivo = StringFormat("Spread alto (%.2f > %.2f)", spread, InpSpreadMax);
      return false;
     }
   if(!DentroOrario())
     {
      motivo = "Fuori orario di trading";
      return false;
     }
   if(!LontanoDaiBordiSessione())
     {
      motivo = "Apertura/chiusura sessione (rollover)";
      return false;
     }
   motivo = "OK";
   return true;
  }

//+------------------------------------------------------------------+
//| STATO: nuovo giorno -> nuovo saldo di riferimento                |
//+------------------------------------------------------------------+
void NuovoGiorno(const datetime oggi)
  {
   g_giornoCorrente    = oggi;
   g_saldoInizioGiorno = AccountInfoDouble(ACCOUNT_BALANCE);
   GVSet("GIORNO", (double)(long)oggi);
   GVSet("SALDOGIORNO", g_saldoInizioGiorno);
   PrintFormat("GabbiaGold: nuovo giorno %s - saldo di riferimento %.2f",
               TimeToString(oggi, TIME_DATE), g_saldoInizioGiorno);
  }

//+------------------------------------------------------------------+
//| STATO: ricostruzione dopo riavvio (posizioni + GlobalVariable)   |
//+------------------------------------------------------------------+
void CaricaStato()
  {
   double equity = AccountInfoDouble(ACCOUNT_EQUITY);

//--- saldo di inizio giornata
   datetime oggi          = InizioGiorno(TimeCurrent());
   datetime giornoSalvato = (datetime)(long)GVGet("GIORNO", 0.0);
   double   saldoSalvato  = GVGet("SALDOGIORNO", 0.0);
   if(giornoSalvato == oggi && saldoSalvato > 0.0)
     {
      g_giornoCorrente    = oggi;
      g_saldoInizioGiorno = saldoSalvato;
     }
   else
      NuovoGiorno(oggi);

//--- blocco giornaliero
   g_giornoBlocco = (datetime)(long)GVGet("BLOCCOGIORNO", 0.0);

//--- picco equity e blocco da drawdown
   g_piccoEquity = GVGet("PICCO", 0.0);
   g_bloccoDD    = (GVGet("BLOCCODD", 0.0) > 0.5);
   int ultimoDeinit = (int)MathRound(GVGet("DEINIT", -1.0));
   GVSet("DEINIT", -1.0);
   // "riavvio manuale" = parametro di reset oppure EA rimosso dal grafico e rimesso
   if(g_bloccoDD && (InpResetBloccoDD || ultimoDeinit == REASON_REMOVE))
     {
      g_bloccoDD    = false;
      g_piccoEquity = equity;
      GVSet("BLOCCODD", 0.0);
      Print("GabbiaGold: blocco da drawdown RIMOSSO dal riavvio manuale. Picco equity azzerato all'equity attuale.");
     }
   if(g_piccoEquity <= 0.0)
      g_piccoEquity = equity;
   GVSet("PICCO", g_piccoEquity);

//--- direzione e lotto del ciclo: prima dalle posizioni aperte, poi dalla GlobalVariable
   StatoPosizioni s;
   ScanPosizioni(s);
   if(s.nPrinc > 0)
     {
      if(s.nPrincBuy > s.nPrincSell)
         g_direzione = 1;
      else
         if(s.nPrincSell > s.nPrincBuy)
            g_direzione = -1;
         else
            g_direzione = (s.tipoUltimaPrinc == POSITION_TYPE_BUY) ? 1 : -1;
      g_lottoCiclo = s.volumePrinc;
      GVSet("DIREZIONE", (double)g_direzione);
     }
   else
      g_direzione = (int)MathRound(GVGet("DIREZIONE", 0.0));

   PrintFormat("GabbiaGold: stato ricostruito - direzione %d, principali %d, coperture %d, picco %.2f, blocco DD %s, saldo giorno %.2f",
               g_direzione, s.nPrinc, s.nCop, g_piccoEquity, (g_bloccoDD ? "SI" : "NO"), g_saldoInizioGiorno);
  }

//+------------------------------------------------------------------+
//| PANNELLO SUL GRAFICO                                             |
//+------------------------------------------------------------------+
void AggiornaPannello(const StatoPosizioni &s)
  {
   string valuta   = AccountInfoString(ACCOUNT_CURRENCY);
   double equity   = AccountInfoDouble(ACCOUNT_EQUITY);
   double plGiorno = equity - g_saldoInizioGiorno;
   double plPerc   = (g_saldoInizioGiorno > 0.0) ? plGiorno / g_saldoInizioGiorno * 100.0 : 0.0;
   double ddPerc   = (g_piccoEquity > 0.0) ? (g_piccoEquity - equity) / g_piccoEquity * 100.0 : 0.0;
   double lotto    = (g_lottoCiclo > 0.0) ? g_lottoCiclo : CalcolaLotto();
   bool   bloccoG  = (g_giornoBlocco == InizioGiorno(TimeCurrent()));

   string dirTxt   = (g_direzione == 1) ? "BUY (rialzo)" : (g_direzione == -1 ? "SELL (ribasso)" : "in attesa");
   string trendTxt = (g_trend == 1) ? "EMA veloce > lenta" : (g_trend == -1 ? "EMA veloce < lenta" : "dati non pronti");

   MqlTick tick;
   double spread = 0.0;
   if(SymbolInfoTick(_Symbol, tick))
      spread = tick.ask - tick.bid;

   string txt = "=== GabbiaGold ===\n";
   txt += StringFormat("Direzione: %s   [%s]\n", dirTxt, trendTxt);
   txt += StringFormat("Principali: %d / %d    Coperture: %d / %d\n", s.nPrinc, InpMaxPrincipali, s.nCop, InpMaxCoperture);
   txt += StringFormat("Lotto attuale: %.2f  (prossimo ciclo: %.2f)\n", lotto, CalcolaLotto());
   txt += StringFormat("Basket netto: %.2f %s%s\n", s.profittoNetto, valuta,
                       (InpBasketTP_EUR > 0.0 ? StringFormat("  (target %.2f)", InpBasketTP_EUR) : ""));
   txt += StringFormat("P/L giorno: %.2f %s (%.2f%%)   saldo inizio: %.2f\n", plGiorno, valuta, plPerc, g_saldoInizioGiorno);
   txt += StringFormat("Picco equity: %.2f   Drawdown: %.2f%% (max %.1f%%)\n", g_piccoEquity, ddPerc, InpDrawdownMax);
   txt += StringFormat("Blocco giornaliero: %s\n", (bloccoG ? "ATTIVO (riprende domani)" : "no"));
   txt += StringFormat("Blocco drawdown: %s\n", (g_bloccoDD ? "ATTIVO (riavviare a mano)" : "no"));
   txt += StringFormat("Spread: %.2f   Filtri: %s\n", spread, g_statoFiltri);
   Comment(txt);
  }

//+------------------------------------------------------------------+
//| LOGICA: nuove aperture (prima entrata, gabbia, coperture)        |
//+------------------------------------------------------------------+
void GestisciAperture(const StatoPosizioni &s)
  {
   string motivo = "";
   if(!PossoAprire(motivo))
     {
      g_statoFiltri = motivo;
      return;
     }
   g_statoFiltri = "OK";

   if((long)TimeCurrent() - (long)g_ultimaApertura < (long)InpCooldownSec)
     {
      g_statoFiltri = "Cooldown";
      return;
     }

   MqlTick tick;
   if(!SymbolInfoTick(_Symbol, tick))
      return;

   ENUM_ORDER_TYPE tipoPrinc = (g_direzione == 1) ? ORDER_TYPE_BUY : ORDER_TYPE_SELL;
   ENUM_ORDER_TYPE tipoCop   = (g_direzione == 1) ? ORDER_TYPE_SELL : ORDER_TYPE_BUY;

//--- 1) Prima entrata: nessuna posizione -> nuovo ciclo con lotto ricalcolato
   if(s.nPrinc + s.nCop == 0)
     {
      g_lottoCiclo = CalcolaLotto();
      ApriPosizione(tipoPrinc, g_lottoCiclo, InpSL_Posizione, InpTP_Principale, g_magicPrinc, "P1");
      return;
     }

//--- restano solo coperture (principali chiuse a SL/TP): si attende che chiudano
   if(s.nPrinc == 0)
      return;

   if(g_lottoCiclo <= 0.0)
      g_lottoCiclo = NormalizzaLotto(s.volumePrinc);

   double rif = s.prezzoUltimaPrinc;
   double eps = g_point * 0.5;

   if(g_direzione == 1)
     {
      double prezzo = tick.ask;   // i buy si aprono ad Ask: confronto omogeneo
      //--- 2) gabbia: il prezzo è avanzato di Passo a favore
      if(prezzo >= rif + InpPasso - eps && s.nPrinc < InpMaxPrincipali)
         ApriPosizione(tipoPrinc, g_lottoCiclo, InpSL_Posizione, InpTP_Principale, g_magicPrinc,
                       StringFormat("P%d", s.nPrinc + 1));
      //--- 3) copertura: il prezzo è tornato indietro di Passo
      else
         if(prezzo <= rif - InpPasso + eps && s.nCop < InpMaxCoperture)
            ApriPosizione(tipoCop, g_lottoCiclo, InpSL_Posizione, InpTP_Copertura, g_magicCop, "COP");
     }
   else
     {
      double prezzo = tick.bid;   // i sell si aprono a Bid
      if(prezzo <= rif - InpPasso + eps && s.nPrinc < InpMaxPrincipali)
         ApriPosizione(tipoPrinc, g_lottoCiclo, InpSL_Posizione, InpTP_Principale, g_magicPrinc,
                       StringFormat("P%d", s.nPrinc + 1));
      else
         if(prezzo >= rif + InpPasso - eps && s.nCop < InpMaxCoperture)
            ApriPosizione(tipoCop, g_lottoCiclo, InpSL_Posizione, InpTP_Copertura, g_magicCop, "COP");
     }
  }

//+------------------------------------------------------------------+
//| OnInit                                                           |
//+------------------------------------------------------------------+
int OnInit()
  {
//--- controllo parametri
   if(InpEMAVeloce < 1 || InpEMALenta < 2 || InpEMAVeloce >= InpEMALenta)
     {
      Print("GabbiaGold: periodi EMA non validi (la veloce deve essere minore della lenta)");
      return INIT_PARAMETERS_INCORRECT;
     }
   if(InpPasso <= 0.0 || InpMaxPrincipali < 1 || InpMaxCoperture < 0 || InpCooldownSec < 0)
     {
      Print("GabbiaGold: parametri della gabbia non validi");
      return INIT_PARAMETERS_INCORRECT;
     }
   if(InpLottoBase <= 0.0 || InpLottoMax < InpLottoBase || InpSaldoPerGradino < 0.0)
     {
      Print("GabbiaGold: parametri del lotto non validi");
      return INIT_PARAMETERS_INCORRECT;
     }
   if(InpSL_Posizione <= 0.0)
     {
      Print("GabbiaGold: lo stop loss per posizione è obbligatorio (InpSL_Posizione > 0)");
      return INIT_PARAMETERS_INCORRECT;
     }
   if(InpOraInizio < 0 || InpOraInizio > 23 || InpOraFine < 0 || InpOraFine > 23 ||
      InpMinutoInizio < 0 || InpMinutoInizio > 59 || InpMinutoFine < 0 || InpMinutoFine > 59)
     {
      Print("GabbiaGold: orari non validi");
      return INIT_PARAMETERS_INCORRECT;
     }
   if(InpMaxTentativi < 1 || InpSlippagePunti < 0)
     {
      Print("GabbiaGold: parametri di esecuzione non validi");
      return INIT_PARAMETERS_INCORRECT;
     }
   if(InpMaxCoperture > 0 && InpTP_Copertura <= 0.0)
      Print("GabbiaGold: ATTENZIONE - coperture senza TP: si chiuderanno solo a SL o all'inversione");

//--- le coperture richiedono un conto hedging
   ENUM_ACCOUNT_MARGIN_MODE modoConto = (ENUM_ACCOUNT_MARGIN_MODE)AccountInfoInteger(ACCOUNT_MARGIN_MODE);
   if(modoConto != ACCOUNT_MARGIN_MODE_RETAIL_HEDGING)
     {
      Print("GabbiaGold: il conto NON è hedging. Le coperture richiedono un conto hedging. EA disattivato.");
      return INIT_FAILED;
     }
   if(StringFind(_Symbol, "XAU") < 0)
      PrintFormat("GabbiaGold: ATTENZIONE - il simbolo %s non sembra oro (XAUUSD). I parametri in USD sono pensati per l'oro.", _Symbol);

//--- proprietà del simbolo
   g_digits   = (int)SymbolInfoInteger(_Symbol, SYMBOL_DIGITS);
   g_point    = SymbolInfoDouble(_Symbol, SYMBOL_POINT);
   g_tickSize = SymbolInfoDouble(_Symbol, SYMBOL_TRADE_TICK_SIZE);
   if(g_point <= 0.0)
      g_point = MathPow(10.0, -g_digits);

//--- magic: principali e coperture separate
   g_magicPrinc = InpMagic;
   g_magicCop   = InpMagic + 1;

//--- configurazione CTrade
   g_trade.SetExpertMagicNumber((ulong)g_magicPrinc);
   g_trade.SetDeviationInPoints((ulong)InpSlippagePunti);
   ImpostaFilling();
   g_trade.SetMarginMode();
   g_trade.SetAsyncMode(false);
   g_trade.LogLevel(LOG_LEVEL_ERRORS);

//--- indicatori
   g_hEMAVeloce = iMA(_Symbol, InpTimeframe, InpEMAVeloce, 0, MODE_EMA, PRICE_CLOSE);
   g_hEMALenta  = iMA(_Symbol, InpTimeframe, InpEMALenta, 0, MODE_EMA, PRICE_CLOSE);
   if(g_hEMAVeloce == INVALID_HANDLE || g_hEMALenta == INVALID_HANDLE)
     {
      PrintFormat("GabbiaGold: impossibile creare le EMA (errore %d)", GetLastError());
      return INIT_FAILED;
     }

//--- prefisso GlobalVariable: magic + simbolo + conto
   g_prefGV = StringFormat("GG_%I64d_%s_%I64d_", InpMagic, _Symbol, AccountInfoInteger(ACCOUNT_LOGIN));
   if(MQLInfoInteger(MQL_TESTER))
     {
      // nel tester ogni passaggio deve partire pulito
      if(GlobalVariablesDeleteAll(g_prefGV) < 0)
         Print("GabbiaGold: impossibile pulire le GlobalVariable del tester");
     }

   CaricaStato();

   StatoPosizioni s;
   ScanPosizioni(s);
   g_statoFiltri = "avvio";
   AggiornaPannello(s);

   PrintFormat("GabbiaGold avviato su %s - lotto attuale %.2f, spread max %.2f, SL %.2f, passo %.2f",
               _Symbol, CalcolaLotto(), InpSpreadMax, InpSL_Posizione, InpPasso);
   return INIT_SUCCEEDED;
  }

//+------------------------------------------------------------------+
//| OnDeinit                                                         |
//+------------------------------------------------------------------+
void OnDeinit(const int reason)
  {
// salva il motivo: REASON_REMOVE (EA tolto dal grafico) vale come "riavvio manuale"
   GVSet("DEINIT", (double)reason);
   if(g_hEMAVeloce != INVALID_HANDLE && !IndicatorRelease(g_hEMAVeloce))
      Print("GabbiaGold: impossibile rilasciare l'EMA veloce");
   if(g_hEMALenta != INVALID_HANDLE && !IndicatorRelease(g_hEMALenta))
      Print("GabbiaGold: impossibile rilasciare l'EMA lenta");
   Comment("");
  }

//+------------------------------------------------------------------+
//| OnTick                                                           |
//+------------------------------------------------------------------+
void OnTick()
  {
//--- 0) cambio giorno
   datetime oggi = InizioGiorno(TimeCurrent());
   if(oggi != g_giornoCorrente)
      NuovoGiorno(oggi);

   double equity = AccountInfoDouble(ACCOUNT_EQUITY);
   StatoPosizioni s;
   ScanPosizioni(s);

//--- 1) drawdown massimo totale dal picco di equity
   if(!g_bloccoDD)
     {
      if(equity > g_piccoEquity)
        {
         g_piccoEquity = equity;
         GVSet("PICCO", g_piccoEquity);
        }
      if(InpDrawdownMax > 0.0 && equity <= g_piccoEquity * (1.0 - InpDrawdownMax / 100.0))
        {
         g_bloccoDD = true;
         GVSet("BLOCCODD", 1.0);
         PrintFormat("GabbiaGold: DRAWDOWN MASSIMO RAGGIUNTO (equity %.2f, picco %.2f, limite %.1f%%). Bot fermato fino a riavvio manuale.",
                     equity, g_piccoEquity, InpDrawdownMax);
        }
     }
   if(g_bloccoDD)
     {
      if(s.nPrinc + s.nCop > 0)
        {
         ChiudiTutto("drawdown massimo");
         ScanPosizioni(s);
        }
      g_statoFiltri = "FERMO (drawdown)";
      AggiornaPannello(s);
      return;
     }

//--- 2) perdita massima giornaliera
   bool bloccoGiorno = (g_giornoBlocco == oggi);
   if(!bloccoGiorno && InpPerditaMaxGiorno > 0.0 &&
      equity <= g_saldoInizioGiorno * (1.0 - InpPerditaMaxGiorno / 100.0))
     {
      g_giornoBlocco = oggi;
      GVSet("BLOCCOGIORNO", (double)(long)oggi);
      bloccoGiorno = true;
      PrintFormat("GabbiaGold: PERDITA GIORNALIERA MASSIMA RAGGIUNTA (equity %.2f, saldo inizio %.2f, limite %.1f%%). Trading bloccato fino a domani.",
                  equity, g_saldoInizioGiorno, InpPerditaMaxGiorno);
     }
   if(bloccoGiorno)
     {
      if(s.nPrinc + s.nCop > 0)
        {
         ChiudiTutto("perdita giornaliera massima");
         ScanPosizioni(s);
        }
      g_statoFiltri = "Bloccato fino a domani";
      AggiornaPannello(s);
      return;
     }

//--- 3) trend dalle EMA
   AggiornaTrend();
   if(g_direzione == 0 && g_trend != 0)
     {
      g_direzione = g_trend;
      GVSet("DIREZIONE", (double)g_direzione);
     }

//--- 4) inversione: incrocio EMA opposto -> chiude tutto e riparte nella nuova direzione
   if(g_trend != 0 && g_trend != g_direzione)
     {
      PrintFormat("GabbiaGold: incrocio EMA - inversione da %s a %s",
                  (g_direzione == 1 ? "BUY" : "SELL"), (g_trend == 1 ? "BUY" : "SELL"));
      if(s.nPrinc + s.nCop > 0)
         ChiudiTutto("inversione del trend");
      g_direzione  = g_trend;
      g_lottoCiclo = 0.0;
      GVSet("DIREZIONE", (double)g_direzione);
      ScanPosizioni(s);
     }
//--- sicurezza: posizioni residue nel verso sbagliato (es. chiusura fallita) -> ritenta
//    principali devono essere nel verso della direzione, coperture nel verso opposto
   if((g_direzione == 1 && (s.nPrincSell > 0 || s.nCopBuy > 0)) ||
      (g_direzione == -1 && (s.nPrincBuy > 0 || s.nCopSell > 0)))
     {
      ChiudiTutto("posizioni residue della direzione precedente");
      g_lottoCiclo = 0.0;
      ScanPosizioni(s);
     }

//--- 5) basket take profit
   if(InpBasketTP_EUR > 0.0 && s.nPrinc + s.nCop > 0 && s.profittoNetto >= InpBasketTP_EUR)
     {
      PrintFormat("GabbiaGold: basket TP raggiunto (%.2f >= %.2f)", s.profittoNetto, InpBasketTP_EUR);
      ChiudiTutto("basket take profit");
      g_lottoCiclo = 0.0;
      ScanPosizioni(s);
     }

//--- 6) nuove aperture
   if(g_direzione != 0)
      GestisciAperture(s);
   else
      g_statoFiltri = "In attesa delle EMA";

   ScanPosizioni(s);
   AggiornaPannello(s);
  }

//+------------------------------------------------------------------+
//| OnTradeTransaction: simulazione commissione nel tester           |
//+------------------------------------------------------------------+
void OnTradeTransaction(const MqlTradeTransaction &trans,
                        const MqlTradeRequest &request,
                        const MqlTradeResult &result)
  {
   if(!InpSimulaCommTester || !MQLInfoInteger(MQL_TESTER))
      return;
   if(trans.type != TRADE_TRANSACTION_DEAL_ADD)
      return;
   if(!HistoryDealSelect(trans.deal))
      return;
   if(HistoryDealGetString(trans.deal, DEAL_SYMBOL) != _Symbol)
      return;
   long magic = HistoryDealGetInteger(trans.deal, DEAL_MAGIC);
   if(magic != g_magicPrinc && magic != g_magicCop)
      return;
   ENUM_DEAL_ENTRY entrata = (ENUM_DEAL_ENTRY)HistoryDealGetInteger(trans.deal, DEAL_ENTRY);
   if(entrata != DEAL_ENTRY_IN)
      return;
   if(HistoryDealGetDouble(trans.deal, DEAL_COMMISSION) != 0.0)
      return;                               // il tester applica già la commissione: non duplicare
   double commissione = InpCommissioneLottoRT * HistoryDealGetDouble(trans.deal, DEAL_VOLUME);
   if(commissione > 0.0 && !TesterWithdrawal(commissione))
      PrintFormat("GabbiaGold: simulazione commissione fallita (%.2f)", commissione);
  }
//+------------------------------------------------------------------+
