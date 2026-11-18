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

    override fun onCreate() {
        super.onCreate()
        val bt = getSystemService(BluetoothManager::class.java)
        trasporto = TrasportoBluetooth(bt?.adapter)
        // Android 14: senza permesso Bluetooth startForeground lancia eccezione e chiude l'app
        val ok = runCatching { startForeground(1, notifica()) }
        if (ok.isFailure) {
            Connessione.imposta("Servizio non avviato: dai il permesso Bluetooth all'app")
            stopSelf(); return
        }
        avviaLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == AZIONE_RIPROVA) {
            // nuovo adattatore scelto o "Riprova": chiudi e ricollega subito
            elm = null
            trasporto.chiudi()
            riprovaOra = true
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
        while (isActive) {
            val connessione = elm ?: connetti()
            if (connessione == null) {
                attendi(ATTESA_SENZA_ADATTATORE)
                continue
            }

            // Biforcazione: o leggo i PID, o ascolto il bus. Mai insieme
            // sullo stesso ELM327: ATMA esclude le richieste PID.
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

            if (!stato.motoreAcceso) {
                // chiave spenta: molla il socket, lascia dormire l'adattatore
                Connessione.imposta(
                    "Adattatore OK (${Connessione.dispositivo}), motore spento. " +
                        "Riprovo tra 60 s, o tocca Riprova a motore acceso.", collegato = true)
                elm = null
                trasporto.chiudi()
                attendi(ATTESA_MOTORE_SPENTO)
            } else {
                Connessione.imposta("Collegato a ${Connessione.dispositivo}: lettura dati attiva", collegato = true)
                TensioneLog.registra(applicationContext, stato.valore("42"))
                delay(INTERVALLO_LETTURA)
            }
        }
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
            Connessione.imposta("Errore con $nome: ${it.message}")
            return null
        }
        Connessione.versione = e.versione
        // prima richiesta lenta (ricerca protocollo): qui, non nel giro da 500 ms
        if (!runCatching { e.centralinaRisponde() }.getOrDefault(false)) {
            Connessione.imposta(
                "$nome risponde (${e.versione}) ma l'auto no: accendi il quadro o il motore. " +
                    "Riprovo tra 10 s.", collegato = true)
            trasporto.chiudi()
            return null
        }
        return e.also { elm = it }
    }

    override fun onDestroy() {
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
            .build()
    }

    companion object {
        private const val CANALE = "lettura"
        private const val INTERVALLO_LETTURA = 500L
        private const val ATTESA_MOTORE_SPENTO = 60_000L
        private const val ATTESA_SENZA_ADATTATORE = 10_000L

        private const val AZIONE_RIPROVA = "cafe.megane.car.RIPROVA"
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

    fun imposta(t: String, collegato: Boolean = false) {
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
