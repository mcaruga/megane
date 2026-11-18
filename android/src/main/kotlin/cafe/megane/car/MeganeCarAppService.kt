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

    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: android.content.Intent): Screen {
            // stato CONDIVISO col PollingService: se ne creassimo uno nuovo qui,
            // la griglia su R-Link2 resterebbe vuota perche a leggere e il service
            val stato = StatoApp.stato(carContext)
                ?: error("Catalogo PID non caricabile")
            runCatching { PollingService.avvia(carContext) }
            return DashboardScreen(carContext, stato)
        }
    }
}
