package cafe.megane.car

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import cafe.megane.core.SessionePista

/**
 * Stato della modalita pista, condiviso tra il servizio GPS, l'elenco su Android Auto
 * e il disegno del circuito. Il GPS e quello del TELEFONO, non dell'auto.
 */
object PistaStato {
    val sessione = SessionePista()
    @Volatile var gpsAttivo = false
    @Volatile var ultimoFixMs = 0L          // orario GPS dell'ultimo punto
    @Volatile var ultimoFixLocale = 0L      // orologio del telefono quando e arrivato
    @Volatile var kmh: Double? = null
    @Volatile var testo = "Sessione ferma"
    @Volatile var messaggio = ""
    @Volatile var messaggioQuando = 0L

    fun avviso(t: String) { messaggio = t; messaggioQuando = System.currentTimeMillis() }
    fun avvisoRecente(): String? = messaggio.takeIf { it.isNotEmpty() && System.currentTimeMillis() - messaggioQuando < 8_000 }

    /** Punti GPS arrivati negli ultimi 3 s. */
    fun gpsVivo() = gpsAttivo && System.currentTimeMillis() - ultimoFixLocale < 3_000

    fun tempoGiroMs(): Long? = if (gpsVivo()) sessione.tempoGiro(ultimoFixMs) else null

    fun stato(): String = when {
        !gpsAttivo -> testo
        !gpsVivo() -> "Cerco il segnale GPS... (all'aperto, telefono con vista sul cielo)"
        !sessione.haTraguardo -> "GPS ok. Metti il traguardo con 'Traguardo qui'"
        !sessione.inGiro -> "Traguardo messo. Il cronometro parte al primo passaggio"
        else -> "Cronometro in corso"
    }
}

/**
 * GPS del telefono per la modalita pista. Parte solo quando tocchi "Avvia sessione"
 * e solo con il permesso posizione. Nessun dato lascia il telefono.
 */
class PistaGpsService : Service(), LocationListener {

    private var lm: LocationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FERMA) { chiudi("Sessione ferma"); return START_NOT_STICKY }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            PistaStato.testo = "Manca il permesso posizione: aprilo una volta dall'app Megane sul telefono"
            stopSelf(); return START_NOT_STICKY
        }
        val ok = runCatching { startForeground(2, notifica(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
        if (ok.isFailure) {
            PistaStato.testo = "Android non mi lascia usare il GPS in background: ${ok.exceptionOrNull()?.javaClass?.simpleName}"
            stopSelf(); return START_NOT_STICKY
        }
        val gestore = getSystemService(LocationManager::class.java).also { lm = it }
        val r = runCatching {
            @Suppress("MissingPermission")
            gestore.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this, Looper.getMainLooper())
        }
        if (r.isFailure) { chiudi("GPS non disponibile: ${r.exceptionOrNull()?.message}"); return START_NOT_STICKY }
        PistaStato.gpsAttivo = true
        PistaStato.testo = "Sessione attiva"
        return START_NOT_STICKY
    }

    private fun chiudi(motivo: String) {
        runCatching { lm?.removeUpdates(this) }
        PistaStato.gpsAttivo = false
        PistaStato.testo = motivo
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { lm?.removeUpdates(this) }
        PistaStato.gpsAttivo = false
        super.onDestroy()
    }

    override fun onLocationChanged(l: Location) {
        PistaStato.ultimoFixMs = l.time
        PistaStato.ultimoFixLocale = System.currentTimeMillis()
        PistaStato.kmh = if (l.hasSpeed()) l.speed * 3.6 else null
        // precisione scarsa = punti sbagliati: li scarto invece di disegnare zig-zag
        if (l.hasAccuracy() && l.accuracy > 25f) return
        val giro = PistaStato.sessione.fix(l.latitude, l.longitude, l.time)
        if (giro != null) PistaStato.avviso("Giro ${giro.numero}: ${SessionePista.formatta(giro.tempoMs)}")
    }

    @Deprecated("API vecchie")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { PistaStato.testo = "GPS spento nelle impostazioni del telefono" }

    private fun notifica(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANALE, "Modalita pista", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CANALE)
            .setContentTitle("Megane: modalita pista")
            .setContentText("GPS attivo per il cronometro")
            .setSmallIcon(R.drawable.ic_generico)
            .addAction(Notification.Action.Builder(null, "Ferma",
                android.app.PendingIntent.getService(this, 2,
                    Intent(this, PistaGpsService::class.java).setAction(FERMA),
                    android.app.PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
    }

    companion object {
        private const val CANALE = "pista"
        private const val FERMA = "cafe.megane.car.PISTA_FERMA"

        fun avvia(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, PistaGpsService::class.java)) }
                .onFailure { PistaStato.testo = "Servizio GPS non avviato: apri l'app sul telefono" }
        }

        fun ferma(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, PistaGpsService::class.java).setAction(FERMA)) }
            PistaStato.gpsAttivo = false
        }

        fun permessoOk(ctx: Context) = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
