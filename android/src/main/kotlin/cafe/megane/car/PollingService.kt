package cafe.megane.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cafe.megane.core.Elm327
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Loop di lettura. Gira in foreground service perche deve sopravvivere
 * mentre Android Auto e in primo piano sullo schermo R-Link2.
 *
 * Politica batteria, come richiesto:
 *  - a motore spento il socket si CHIUDE e si riprova ogni 60 s
 *  - a motore acceso si legge ogni 500 ms
 *  - nessuna lettura mantiene sveglio l'adattatore a chiave spenta
 */
class PollingService : LifecycleService() {

    private lateinit var trasporto: TrasportoBluetooth
    private var elm: Elm327? = null
    private var monitorAttivo: cafe.megane.core.ElmMonitor? = null
    private var fallimenti = 0

    override fun onCreate() {
        super.onCreate()
        attivo = true
        Connessione.ctx = applicationContext
        val bt = getSystemService(BluetoothManager::class.java)
        trasporto = TrasportoBluetooth(bt?.adapter)
        // Android 14: senza permesso Bluetooth startForeground lancia eccezione e chiude l'app
        val ok = runCatching { startForeground(1, notifica()) }
        if (ok.isFailure) {
            Connessione.imposta("Servizio non avviato: dai il permesso Bluetooth all'app")
            stopSelf(); return
        }
        avviaLoop()
        // controllo aggiornamenti: subito e poi ogni 30 minuti (poche centinaia di byte)
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { Aggiornamenti.controlla(applicationContext) }
                delay(30 * 60_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == AZIONE_FERMA) {
            // libera il vLinker per altre app (aggiornamento firmware, app del produttore)
            Connessione.imposta("Megane ferma: vLinker libero per altre app. Tocca Riprova per ripartire.")
            elm = null
            trasporto.chiudi()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == AZIONE_RIPROVA) {
            // nuovo adattatore scelto o "Riprova": chiudi e ricollega subito
            elm = null
            trasporto.chiudi()
            riprovaOra = true
            fallimenti = 0
        }
        return START_STICKY
    }

    /** Attesa interrompibile dal pulsante "Riprova" sul telefono. */
    private suspend fun attendi(ms: Long) {
        var resto = ms
        while (resto > 0 && !riprovaOra) { delay(500); resto -= 500 }
        riprovaOra = false
    }

    private fun avviaLoop() = lifecycleScope.launch(Dispatchers.IO) {
        val stato = StatoApp.stato(applicationContext) ?: return@launch
        stato.registra = { runCatching { Diagnosi.evento(applicationContext, it) } }
        while (isActive) {
            val connessione = elm ?: connetti()
            if (connessione == null) {
                // vLinker assente o occupato: diradare i tentativi, cosi non lo
                // si strappa ad altre app (es. aggiornamento firmware)
                fallimenti++
                attendi(when {
                    fallimenti <= 3 -> ATTESA_SENZA_ADATTATORE
                    fallimenti <= 6 -> 60_000L
                    else -> 5 * 60_000L
                })
                continue
            }
            fallimenti = 0

            // Biforcazione: o leggo i PID, o ascolto il bus. Mai insieme
            // sullo stesso ELM327: ATMA esclude le richieste PID.
            // lettura (solo lettura) delle altre centraline: una volta, poi si riparte puliti
            if (stato.modalita == StatoVeicolo.Modalita.LETTURA_ECU) {
                leggiAltreCentraline(connessione)
                stato.modalita = StatoVeicolo.Modalita.PID
                elm = null; trasporto.chiudi()     // dopo ATSH/ATCRA riparte da zero con ATZ
                continue
            }

            if (stato.modalita == StatoVeicolo.Modalita.CAN_MONITOR) {
                ascoltaCan(stato)          // bloccante finche l'utente non esce dal monitor
                continue
            }

            val giro = runCatching { stato.aggiorna(connessione) }
            if (giro.isFailure) {
                Connessione.imposta("Connessione persa con ${Connessione.dispositivo}: ricollego")
                elm = null; trasporto.chiudi()
                continue
            }

            if (!stato.quadroAcceso) {
                if (stato.inAttesa()) {
                    // risposta mancata: non chiudere subito, ritenta tra poco
                    delay(1000); continue
                }
                // la centralina non risponde: quadro spento. Molla il socket, lascia dormire l'adattatore
                Connessione.imposta(
                    "vLinker OK (${Connessione.dispositivo}) ma la centralina non risponde: quadro spento? " +
                        "Riprovo tra 20 s, o tocca Riprova.", collegato = true)
                elm = null
                trasporto.chiudi()
                attendi(ATTESA_MOTORE_SPENTO)
            } else if (!stato.motoreAcceso) {
                // quadro acceso, motore fermo (es. Start&Stop al semaforo): resta collegato
                Connessione.imposta("Collegato a ${Connessione.dispositivo}: quadro acceso, motore fermo", collegato = true)
                TensioneLog.registra(applicationContext, stato.valore("42"))
                delay(2000)
            } else {
                Connessione.imposta("Collegato a ${Connessione.dispositivo}: motore acceso, lettura attiva", collegato = true)
                TensioneLog.registra(applicationContext, stato.valore("42"))
                delay(INTERVALLO_LETTURA)
            }
        }
    }

    /** Scansione delle centraline: passa solo dalla lista bianca di sola lettura (vedi Centraline.kt). */
    private fun leggiAltreCentraline(e: Elm327) {
        ScansioneEcu.inCorso = true
        ScansioneEcu.progresso = "Avvio..."
        runCatching { Diagnosi.evento(applicationContext, "ECU: lettura altre centraline (sola lettura) avviata") }
        val r = runCatching {
            cafe.megane.core.LetturaCentraline(e, { ScansioneEcu.progresso = it }).scansiona()
        }
        r.onSuccess { lista ->
            val quando = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ITALY).format(java.util.Date())
            ScansioneEcu.salva(applicationContext, cafe.megane.core.Scansione(quando, lista))
            runCatching { Diagnosi.evento(applicationContext, "ECU: trovate ${lista.size} centraline: " +
                lista.joinToString { "%03X".format(it.richiesta) }) }
        }.onFailure {
            ScansioneEcu.progresso = "Errore: ${it.message}"
            runCatching { Diagnosi.evento(applicationContext, "ECU: lettura fallita: ${it.message}") }
        }
        ScansioneEcu.inCorso = false
    }

    /**
     * Ascolto passivo del bus, con ogni frame passato al correlatore.
     * Qui si collega il logger al correlatore: era il pezzo mancante.
     * Esce quando l'utente riporta la modalita su PID (da CanScreen).
     */
    private fun ascoltaCan(stato: StatoVeicolo) {
        val monitor = trasporto.monitorElm() ?: return
        monitorAttivo = monitor
        runCatching {
            monitor.ascolta { frame ->
                stato.correlatore.osserva(frame)
                stato.riepilogo.osserva(frame)
                stato.contaFrame()
                // appena l'utente lascia il monitor, interrompi l'ascolto
                if (stato.modalita != StatoVeicolo.Modalita.CAN_MONITOR) monitor.stop()
            }
        }.onFailure { elm = null; trasporto.chiudi() }
        monitorAttivo = null
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun connetti(): Elm327? {
        val device = runCatching { trasporto.scegli(Connessione.indirizzoScelto(this)) }.getOrNull()
        if (device == null) {
            Connessione.imposta("Nessun adattatore: accoppia il vLinker nel Bluetooth del telefono (PIN 1234) e sceglilo qui sotto")
            return null
        }
        val nome = runCatching { device.name }.getOrNull() ?: device.address
        Connessione.dispositivo = nome
        Connessione.imposta("Mi collego a $nome...")
        val r = trasporto.apri(device)
        val e = r.getOrElse {
            Connessione.imposta("Errore con $nome: ${it.javaClass.simpleName}: ${it.message}")
            return null
        }
        Connessione.versione = e.versione
        // prima richiesta lenta (ricerca protocollo): qui, non nel giro da 500 ms
        if (!runCatching { e.centralinaRisponde() }.getOrDefault(false)) {
            runCatching {
                Diagnosi.evento(applicationContext, "ECU 0100 senza risposta valida: '${e.ultimaRisposta.take(80)}'")
                e.log.forEach { Diagnosi.evento(applicationContext, "HEX ${it.take(160)}") }
            }
            Connessione.imposta(
                "$nome risponde (${e.versione}) ma l'auto no: accendi il quadro o il motore. " +
                    "Riprovo tra 10 s.", collegato = true)
            trasporto.chiudi()
            return null
        }
        return e.also { elm = it }
    }

    override fun onDestroy() {
        attivo = false
        monitorAttivo?.stop()
        trasporto.chiudi()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun notifica(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANALE, "Lettura auto", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CANALE)
            .setContentTitle("Megane")
            .setContentText("Lettura dati in corso")
            .setSmallIcon(R.drawable.ic_generico)
            .addAction(Notification.Action.Builder(null, "Ferma",
                android.app.PendingIntent.getService(this, 1,
                    Intent(this, PollingService::class.java).setAction(AZIONE_FERMA),
                    android.app.PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
    }

    companion object {
        private const val CANALE = "lettura"
        private const val INTERVALLO_LETTURA = 500L
        private const val ATTESA_MOTORE_SPENTO = 20_000L
        private const val ATTESA_SENZA_ADATTATORE = 10_000L

        private const val AZIONE_RIPROVA = "cafe.megane.car.RIPROVA"
        private const val AZIONE_FERMA = "cafe.megane.car.FERMA"

        /** Chiude il socket e ferma il servizio: il vLinker resta libero. */
        @Volatile private var attivo = false

        fun ferma(ctx: Context) {
            if (!attivo) {
                Connessione.imposta("Megane ferma: vLinker libero per altre app. Tocca Riprova per ripartire.")
                return
            }
            runCatching {
                ctx.startService(Intent(ctx, PollingService::class.java).setAction(AZIONE_FERMA))
            }
        }
        @Volatile private var riprovaOra = false

        /** Parte solo col permesso Bluetooth: senza, Android 14 chiude l'app. */
        fun avvia(ctx: Context, riprova: Boolean = false) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.BLUETOOTH_CONNECT,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Connessione.imposta("Manca il permesso Bluetooth: aprilo dalla app sul telefono")
                return
            }
            val i = Intent(ctx, PollingService::class.java)
            if (riprova) i.action = AZIONE_RIPROVA
            runCatching { ctx.startForegroundService(i) }
                .onFailure { Connessione.imposta("Servizio non avviato: apri l'app sul telefono") }
        }
    }
}

/** Stato della connessione, mostrato sul telefono e su R-Link2. */
object Connessione {
    @Volatile var testo: String = "Avvio..."; private set
    @Volatile var collegato: Boolean = false; private set
    @Volatile var dispositivo: String? = null
    @Volatile var versione: String = ""
    /** Per scrivere gli eventi nel registro di [Diagnosi]. */
    @Volatile var ctx: Context? = null

    fun imposta(t: String, collegato: Boolean = false) {
        if (t != testo) ctx?.let { runCatching { Diagnosi.evento(it, "BT: $t") } }
        testo = t; this.collegato = collegato
    }

    private const val PREFS = "connessione"
    private const val CHIAVE = "indirizzo_dongle"

    fun indirizzoScelto(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHIAVE, null)

    fun scegli(ctx: Context, indirizzo: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHIAVE, indirizzo).apply()
    }
}

/** Singleton dello stato, condiviso tra service, Android Auto e telefono. */
object StatoApp {
    @Volatile private var istanza: StatoVeicolo? = null

    fun stato(ctx: Context): StatoVeicolo? {
        istanza?.let { return it }
        return runCatching { StatoVeicolo.creaDaContext(ctx) }
            .getOrNull()
            ?.also { istanza = it }
    }
}
