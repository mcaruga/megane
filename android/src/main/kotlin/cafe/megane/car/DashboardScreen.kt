package cafe.megane.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import cafe.megane.core.PidDef

/**
 * Schermata che appare sul display R-Link2 via Android Auto.
 *
 * Griglia di riquadri personalizzabile: l'utente sceglie quali parametri
 * vedere da [SelezioneParametriScreen]. Nessun comando verso l'auto.
 */
class DashboardScreen(
    carContext: CarContext,
    private val stato: StatoVeicolo,
) : Screen(carContext) {

    // ridisegna la griglia ogni secondo mentre e visibile: senza, i valori restano fermi
    init {
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            private val h = android.os.Handler(android.os.Looper.getMainLooper())
            private val giro = object : Runnable {
                override fun run() { invalidate(); h.postDelayed(this, 1000) }
            }
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { h.postDelayed(giro, 1000) }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { h.removeCallbacks(giro) }
        })
    }


    override fun onGetTemplate(): Template {
        val griglia = ItemList.Builder().apply {
            stato.parametriScelti.forEach { def ->
                addItem(riquadro(def, stato.valore(def.pid)))
            }
        }.build()

        return GridTemplate.Builder()
            .setTitle(when {
                stato.motoreAcceso -> "Megane"
                stato.quadroAcceso -> "Motore fermo"
                Connessione.collegato -> "Motore spento"
                else -> "vLinker non collegato"
            })
            .setSingleList(griglia)
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Codici")
                            .setOnClickListener { screenManager.push(DtcScreen(carContext, stato)) }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Scegli dati")
                            .setOnClickListener {
                                screenManager.push(SelezioneParametriScreen(carContext, stato))
                            }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("CAN")
                            .setOnClickListener { screenManager.push(CanScreen(carContext, stato)) }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun riquadro(def: PidDef, valore: Double?): GridItem {
        val testo = valore?.let { "%.1f %s".format(it, def.unita) } ?: "--"
        val allarme = valore?.let { def.fuoriRange(it) } ?: false
        return GridItem.Builder()
            .setTitle(def.traduzione)
            .setText(testo)
            .setImage(
                Icone.perPid(carContext, def.pid),
                if (allarme) GridItem.IMAGE_TYPE_ICON else GridItem.IMAGE_TYPE_ICON
            )
            .build()
    }
}

/**
 * Lista completa dei parametri con etichetta
 * "originale [traduzione] [modificabile o no]", come richiesto.
 * Tocco = mostra o nasconde il riquadro sul cruscotto R-Link2.
 */
class SelezioneParametriScreen(
    carContext: CarContext,
    private val stato: StatoVeicolo,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val lista = ItemList.Builder().apply {
            stato.catalogo.pids.forEach { def ->
                addItem(
                    androidx.car.app.model.Row.Builder()
                        .setTitle(def.etichetta())
                        .addText(def.significato.take(120))
                        .setOnClickListener { stato.alterna(def); invalidate() }
                        .build()
                )
            }
        }.build()

        return androidx.car.app.model.ListTemplate.Builder()
            .setTitle("Parametri disponibili")
            .setSingleList(lista)
            .setHeaderAction(Action.BACK)
            .build()
    }
}

/** Codici guasto con spiegazione in italiano. Nessun pulsante per cancellarli. */
class DtcScreen(
    carContext: CarContext,
    private val stato: StatoVeicolo,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val codici = stato.dtc
        val lista = ItemList.Builder().apply {
            if (codici.isEmpty()) {
                setNoItemsMessage("Nessun codice guasto attivo")
            } else {
                codici.forEach { codice ->
                    addItem(
                        androidx.car.app.model.Row.Builder()
                            .setTitle(codice)
                            .addText(DtcItaliano.spiega(codice))
                            .build()
                    )
                }
            }
        }.build()

        return androidx.car.app.model.ListTemplate.Builder()
            .setTitle("Codici guasto")
            .setSingleList(lista)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
