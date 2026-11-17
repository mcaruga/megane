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
        startForeground(1, notifica())
        avviaLoop()
    }

    private fun avviaLoop() = lifecycleScope.launch(Dispatchers.IO) {
        val stato = StatoApp.stato(applicationContext) ?: return@launch
        while (isActive) {
            val connessione = elm ?: connetti()
            if (connessione == null) {
                delay(ATTESA_SENZA_ADATTATORE)
                continue
            }

            // Biforcazione: o leggo i PID, o ascolto il bus. Mai insieme
            // sullo stesso ELM327: ATMA esclude le richieste PID.
            if (stato.modalita == StatoVeicolo.Modalita.CAN_MONITOR) {
                ascoltaCan(stato)          // bloccante finche l'utente non esce dal monitor
                continue
            }

            runCatching { stato.aggiorna(connessione) }
                .onFailure { elm = null; trasporto.chiudi() }

            if (!stato.motoreAcceso) {
                // chiave spenta: molla il socket, lascia dormire l'adattatore
                elm = null
                trasporto.chiudi()
                delay(ATTESA_MOTORE_SPENTO)
            } else {
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

    private fun connetti(): Elm327? {
        val device = trasporto.dispositiviAccoppiati().firstOrNull() ?: return null
        return trasporto.apri(device).getOrNull()?.also { elm = it }
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

        fun avvia(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PollingService::class.java))
        }
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
