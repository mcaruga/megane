package cafe.megane.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator

/**
 * Punto di ingresso Android Auto. Questo e cio che fa comparire l'app
 * sul display R-Link2: nessuna installazione sull'unita, nessun firmware toccato.
 */
class MeganeCarAppService : CarAppService() {

    override fun onCreate() {
        super.onCreate()
        Connessione.ctx = applicationContext
        Diagnosi.evento(this, "AUTO: servizio creato (Android Auto ci ha cercati)")
    }

    // App installata da APK (build firmata da noi, debuggable): accetta ogni host,
    // la lista ufficiale di esempio puo essere vecchia e bloccare Android Auto.
    override fun createHostValidator(): HostValidator {
        Diagnosi.evento(this, "AUTO: richiesta di collegamento da un host")
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debug) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: android.content.Intent): Screen {
            Diagnosi.evento(carContext, "AUTO: schermata aperta su R-Link2")
            // stato CONDIVISO col PollingService: se ne creassimo uno nuovo qui,
            // la griglia su R-Link2 resterebbe vuota perche a leggere e il service
            val stato = StatoApp.stato(carContext)
                ?: error("Catalogo PID non caricabile")
            runCatching { PollingService.avvia(carContext) }
            return DashboardScreen(carContext, stato)
        }
    }
}
