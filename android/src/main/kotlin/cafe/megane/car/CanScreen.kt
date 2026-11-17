package cafe.megane.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.ListTemplate
import cafe.megane.core.CanCorrelatore

/**
 * Schermata correlatore su schermo R-Link2.
 *
 * Due pulsanti: "Funziona" e "Non funziona". Li premi nel momento in cui
 * provi Sport mode. Alla fine "Analizza" mostra gli ID CAN sospetti.
 * Nessun pulsante invia frame: si marca e si ascolta soltanto.
 */
class CanScreen(
    carContext: CarContext,
    private val stato: StatoVeicolo,
) : Screen(carContext) {

    private val correlatore: CanCorrelatore get() = stato.correlatore
    private val inAscolto: Boolean
        get() = stato.modalita == StatoVeicolo.Modalita.CAN_MONITOR

    override fun onGetTemplate(): Template {
        val sospetti = correlatore.analizza()

        val lista = ItemList.Builder().apply {
            addItem(
                Row.Builder()
                    .setTitle(
                        if (inAscolto) "ASCOLTO ATTIVO - frame ricevuti: ${stato.frameRicevuti}"
                        else "Ascolto fermo. Premi Ascolto ON e prova Sport mode."
                    )
                    .addText(correlatore.statoRaccolta())
                    .build()
            )
            if (sospetti.isEmpty()) {
                addItem(
                    Row.Builder()
                        .setTitle("Nessun ID sospetto")
                        .addText("Marca almeno 2 volte OK e 2 volte KO, poi torna qui")
                        .build()
                )
            } else {
                sospetti.forEach { s ->
                    addItem(
                        Row.Builder()
                            .setTitle("ID ${"%03X".format(s.id)}")
                            .addText("OK ${s.esempioOk}")
                            .addText("KO ${s.esempioKo}")
                            .build()
                    )
                }
            }
        }.build()

        return ListTemplate.Builder()
            .setTitle("Correlatore Sport mode")
            .setSingleList(lista)
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(if (inAscolto) "Ascolto OFF" else "Ascolto ON")
                            .setOnClickListener { commutaAscolto() }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Funziona")
                            .setOnClickListener {
                                correlatore.marca(CanCorrelatore.Esito.OK); invalidate()
                            }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Non va")
                            .setOnClickListener {
                                correlatore.marca(CanCorrelatore.Esito.KO); invalidate()
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /** Commuta la modalita del dongle: il service si biforca di conseguenza. */
    private fun commutaAscolto() {
        stato.modalita = if (inAscolto) StatoVeicolo.Modalita.PID
        else StatoVeicolo.Modalita.CAN_MONITOR
        invalidate()
    }

    init {
        // uscendo dalla schermata, torna ai PID: non lasciare il dongle in ATMA
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                stato.modalita = StatoVeicolo.Modalita.PID
            }
        })
    }
}
