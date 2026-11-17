package cafe.megane.car

import android.content.Context
import androidx.car.app.CarContext
import cafe.megane.core.CanCorrelatore
import cafe.megane.core.Catalogo
import cafe.megane.core.Elm327
import cafe.megane.core.EngineGate
import cafe.megane.core.PidDecoder
import cafe.megane.core.PidDef
import java.util.concurrent.ConcurrentHashMap

/**
 * Stato condiviso tra le schermate. Polling solo a motore acceso:
 * a chiave spenta non apre nemmeno il socket, cosi l'adattatore
 * resta in sleep e la batteria non si scarica.
 */
class StatoVeicolo private constructor(
    val catalogo: Catalogo,
    private val prefs: android.content.SharedPreferences,
) {
    @Volatile var motoreAcceso: Boolean = false; private set
    @Volatile var dtc: List<String> = emptyList(); private set

    /** Correlatore eventi CAN, condiviso con CanScreen. */
    val correlatore = CanCorrelatore()

    /** Modalita di lavoro del dongle. PID e CAN sono mutuamente esclusivi. */
    enum class Modalita { PID, CAN_MONITOR }
    @Volatile var modalita: Modalita = Modalita.PID

    /** Quanti frame il monitor ha passato al correlatore (per mostrare che e vivo). */
    @Volatile var frameRicevuti: Long = 0; private set
    fun contaFrame() { frameRicevuti++ }

    private val valori = ConcurrentHashMap<String, Double>()
    private var elm: Elm327? = null

    /** Riquadri scelti dall'utente, salvati tra un avvio e l'altro. */
    val parametriScelti: List<PidDef>
        get() {
            val salvati = prefs.getStringSet(CHIAVE_SCELTI, null) ?: DEFAULT_SCELTI
            return catalogo.pids.filter { it.pid in salvati }
        }

    fun valore(pid: String): Double? = valori[pid]

    fun alterna(def: PidDef) {
        val attuali = (prefs.getStringSet(CHIAVE_SCELTI, null) ?: DEFAULT_SCELTI).toMutableSet()
        if (!attuali.remove(def.pid)) attuali.add(def.pid)
        prefs.edit().putStringSet(CHIAVE_SCELTI, attuali).apply()
    }

    /** Un giro di lettura. Chiamato da un loop periodico, non qui. */
    fun aggiorna(connessione: Elm327?) {
        val adattatore = connessione ?: elm
        if (adattatore == null) { motoreAcceso = false; return }
        motoreAcceso = EngineGate.motoreAcceso(adattatore)
        if (!motoreAcceso) { valori.clear(); return }

        parametriScelti.forEach { def ->
            adattatore.readPid(def.mode, def.pid)
                ?.let { PidDecoder.decodifica(def, it) }
                ?.let { valori[def.pid] = it }
        }
        // boost reale: serve anche la barometrica, letta sempre
        val map = valori["0B"]; val baro = valori["33"]
        if (map != null && baro != null) valori[DERIVATO_BOOST] = PidDecoder.boostBar(map, baro)

        dtc = adattatore.readDtc()
    }

    companion object {
        const val DERIVATO_BOOST = "BOOST"
        private const val CHIAVE_SCELTI = "parametri_scelti"
        private val DEFAULT_SCELTI = setOf("0C", "05", "42", "0B", "04", "0F")

        fun crea(ctx: CarContext): StatoVeicolo = creaDaContext(ctx)

        /** Usato anche dal service e dalla schermata telefono, non solo da Android Auto. */
        fun creaDaContext(ctx: Context): StatoVeicolo {
            DtcItaliano.inizializza(ctx)
            val testo = ctx.assets.open("pids-it.json").bufferedReader().use { it.readText() }
            return StatoVeicolo(
                PidDecoder.carica(testo),
                ctx.getSharedPreferences("megane", Context.MODE_PRIVATE),
            )
        }
    }
}

/**
 * Spiegazione dei codici guasto in italiano.
 * Carica dtc-it.json dagli asset; se fallisce resta un fallback minimo.
 */
object DtcItaliano {
    @Volatile private var catalogo: cafe.megane.core.DtcCatalogo? = null

    fun inizializza(ctx: android.content.Context) {
        if (catalogo != null) return
        catalogo = runCatching {
            val testo = ctx.assets.open("dtc-it.json").bufferedReader().use { it.readText() }
            cafe.megane.core.DtcCatalogo.carica(testo)
        }.getOrNull()
    }

    fun spiega(codice: String): String =
        catalogo?.spiega(codice)
            ?: "Catalogo DTC non caricato. Codice: $codice"
}
