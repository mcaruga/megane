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
    /** La centralina risponde: quadro acceso, anche a motore fermo (Start&Stop). */
    @Volatile var quadroAcceso: Boolean = false; private set
    private var mancate = 0
    private var ultimiDtc = 0L
    /** Dove scrivere le risposte strane della centralina (diagnosi). */
    var registra: ((String) -> Unit)? = null
    @Volatile var dtc: List<String> = emptyList(); private set

    /** Correlatore eventi CAN, condiviso con CanScreen. */
    val correlatore = CanCorrelatore()

    /** Livello benzina stimato: persistente tra un avvio e l'altro. */
    val serbatoio: cafe.megane.core.StimaSerbatoio = cafe.megane.core.StimaSerbatoio.carica(
        listOf("capacita", "litri", "k", "grezzo", "aggiunti", "valido")
            .associateWith { prefs.getFloat("serb_$it", Float.NaN).toDouble() }
            .filterValues { !it.isNaN() }
    )
    private var serbSalvato = 0L
    private var serbUltimo = 0L
    fun salvaSerbatoio() {
        prefs.edit().apply { serbatoio.salva().forEach { (k, v) -> putFloat("serb_$k", v.toFloat()) } }.apply()
        serbSalvato = System.currentTimeMillis()
    }

    /** Massimo del quadrante (il serbatoio ha la sua capacita), o null per usare quello del catalogo. */
    fun massimo(pid: String): Double? = if (pid == "BENZINA") serbatoio.capacita else null

    /** Riassunto della registrazione CAN (pulsante sul telefono), da condividere. */
    val riepilogo = cafe.megane.core.CanRiepilogo()

    /** Modalita di lavoro del dongle. PID e CAN sono mutuamente esclusivi. */
    enum class Modalita { PID, CAN_MONITOR, LETTURA_ECU }
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
            // ordine di visualizzazione fisso: temperature in fondo
            return catalogo.pids.filter { it.pid in salvati && disponibile(it) }
                .sortedBy { ORDINE.indexOf(it.pid).let { i -> if (i < 0) ORDINE.size else i } }
        }

    fun valore(pid: String): Double? = valori[pid]

    /** Picco raggiunto da questo parametro (lancetta rossa), se ne ha uno. */
    fun picco(pid: String): Double? = PICCO_DI[pid]?.let { valori[it] }

    /** PID che l'auto dichiara di supportare; null finche non e stata fatta la scansione. */
    @Volatile var supportati: Set<String>? = null; private set
    private var tentativiScan = 0
    private val campVel = ArrayDeque<Pair<Long, Double>>()

    /** false se l'auto NON espone questo dato (cosi non si mostrano riquadri sempre vuoti). */
    fun disponibile(def: PidDef): Boolean {
        val s = supportati ?: return true
        return when (def.pid) {
            "BOOST" -> "0B" in s && "33" in s
            "CONS" -> "10" in s && "0D" in s
            "DECEL" -> "0D" in s
            "BENZINA" -> "10" in s || "5E" in s
            else -> def.mode == "calc" || def.pid in s
        }
    }

    fun alterna(def: PidDef) {
        val attuali = (prefs.getStringSet(CHIAVE_SCELTI, null) ?: DEFAULT_SCELTI).toMutableSet()
        if (!attuali.remove(def.pid)) attuali.add(def.pid)
        prefs.edit().putStringSet(CHIAVE_SCELTI, attuali).apply()
    }

    /**
     * Un giro di lettura. Chiamato da un loop periodico, non qui.
     * Si legge finche la centralina risponde (quadro acceso): con lo Start&Stop
     * il motore si ferma al semaforo e non va chiusa la connessione.
     * Tre risposte mancate di fila = quadro spento.
     */
    fun aggiorna(connessione: Elm327?) {
        val adattatore = connessione ?: elm
        if (adattatore == null) { spento(); return }
        val giri = EngineGate.giri(adattatore)
        if (giri == null) {
            mancate++
            if (mancate == 1) registra?.invoke("ECU 010C senza giri: '${adattatore.ultimaRisposta.take(80)}'")
            if (mancate >= 3) spento()
            return
        }
        if (!quadroAcceso) registra?.invoke("ECU risponde: giri $giri ('${adattatore.ultimaRisposta.take(40)}')")
        mancate = 0
        if (supportati == null && tentativiScan < 3) {
            tentativiScan++
            // una volta per accensione: chiede all'auto quali dati sa dare
            val trovati = adattatore.pidSupportati()
            if ("0C" in trovati) {
                supportati = trovati
                registra?.invoke("PID supportati (${trovati.size}): " + trovati.sorted().joinToString(" "))
            } else registra?.invoke("Scansione PID non valida: '${adattatore.ultimaRisposta.take(60)}'")
        }
        quadroAcceso = true
        motoreAcceso = EngineGate.accesoDaGiri(giri)
        valori["0C"] = giri.toDouble()

        val gtAttivo = System.currentTimeMillis() - gtVisto < 15_000
        val scelti = parametriScelti.toMutableList()
        // la scheda GT legge i suoi dati solo mentre e aperta (ogni PID costa tempo sul bus)
        if (gtAttivo) GT_PIDS.forEach { p ->
            catalogo.pids.firstOrNull { it.pid == p }?.let { d ->
                if (disponibile(d) && scelti.none { it.pid == p }) scelti += d
            }
        }
        // la spinta turbo e calcolata: servono MAP e barometrica anche se non scelte
        val daLeggere = scelti.filter { it.mode != "calc" }.toMutableList()
        if (scelti.any { it.pid == "BOOST" }) {
            catalogo.pids.filter { it.pid == "0B" || it.pid == "33" }
                .forEach { if (daLeggere.none { d -> d.pid == it.pid }) daLeggere += it }
        }
        // la stima benzina vive di flusso carburante: 5E se l'auto lo da, altrimenti aria aspirata (MAF)
        if (scelti.any { it.pid == "BENZINA" }) {
            val sup = supportati
            catalogo.pids.filter { it.pid == "10" || (it.pid == "5E" && sup != null && "5E" in sup) }
                .forEach { if (daLeggere.none { d -> d.pid == it.pid }) daLeggere += it }
        }
        daLeggere.forEach { def ->
            if (def.pid == "0C") return@forEach
            adattatore.readPid(def.mode, def.pid)
                ?.let { PidDecoder.decodifica(def, it) }
                ?.let { valori[def.pid] = it }
        }
        val map = valori["0B"]; val baro = valori["33"]
        if (map != null && baro != null) valori[DERIVATO_BOOST] = PidDecoder.boostBar(map, baro)

        // livello benzina: integra il flusso dall'ultimo giro (salta se il buco e lungo: connessione persa)
        val oraS = System.currentTimeMillis()
        val flusso = valori["5E"] ?: valori["10"]?.let { cafe.megane.core.StimaSerbatoio.flussoDaMaf(it) }
        if (flusso != null && serbUltimo != 0L) {
            val dt = (oraS - serbUltimo) / 1000.0
            if (dt in 0.05..3.0) serbatoio.integra(flusso, dt)
        }
        serbUltimo = oraS
        serbatoio.litri?.let { valori["BENZINA"] = it } ?: valori.remove("BENZINA")
        if (oraS - serbSalvato > 30_000) salvaSerbatoio()

        // consumo istantaneo (MAF + velocita) e picchi della giornata: calcolati, nessuna richiesta all'auto
        val maf = valori["10"]; val kmh = valori["0D"]
        if (maf != null && kmh != null) PidDecoder.litriPer100km(maf, kmh)?.let { valori["CONS"] = it }
        else valori.remove("CONS")
        // frenata: quanto rallenti, in g, dal calo di velocita su ~1,5 s (la pressione freni non e in OBD)
        if (kmh != null) {
            val ora0 = System.currentTimeMillis()
            campVel.addLast(ora0 to kmh)
            while (campVel.size > 1 && ora0 - campVel.first().first > 1_500) campVel.removeFirst()
            val prima = campVel.first()
            val dt = (ora0 - prima.first) / 1000.0
            if (dt >= 0.5) valori["DECEL"] = (((prima.second - kmh) / 3.6) / dt / 9.81).coerceAtLeast(0.0)
        }
        PICCO_DI.forEach { (pid, chiave) -> picco(chiave, valori[pid]) }

        // codici guasto: ogni 30 s, non a ogni giro (sono due richieste lente)
        val ora = System.currentTimeMillis()
        if (ora - ultimiDtc > 30_000) { dtc = adattatore.readDtc(); ultimiDtc = ora }
    }

    /** Valore massimo visto da quando e acceso il quadro. */
    private fun picco(chiave: String, v: Double?) {
        if (v != null && v > (valori[chiave] ?: Double.NEGATIVE_INFINITY)) valori[chiave] = v
    }

    /** La scheda GT e aperta: da qui in poi si leggono anche i suoi PID. */
    @Volatile private var gtVisto = 0L
    fun gtRichiesto() { gtVisto = System.currentTimeMillis() }

    /** Ultima lettura mancata ma non ancora la terza: quadro ancora considerato acceso. */
    fun inAttesa() = mancate in 1..2

    private fun spento() {
        motoreAcceso = false; quadroAcceso = false; mancate = 0; ultimiDtc = 0
        supportati = null; tentativiScan = 0; campVel.clear()
        if (serbUltimo != 0L) salvaSerbatoio()
        serbUltimo = 0L
        valori.clear()
    }

    companion object {
        const val DERIVATO_BOOST = "BOOST"
        /** Dati della scheda GT, nell'ordine in cui compaiono. */
        val GT_PIDS = listOf("BOOST", "BENZINA", "49", "11", "DECEL", "10", "0C", "04", "0E", "78", "44", "42", "0D", "05", "68")
        val GT_CALCOLATI = listOf("CONS")
        /** Parametro -> valore di picco (lancetta rossa sul quadrante). */
        val PICCO_DI = mapOf(
            "0C" to "PICCO_RPM", "0D" to "PICCO_KMH", "BOOST" to "PICCO_BOOST",
            "04" to "PICCO_CARICO", "DECEL" to "PICCO_DECEL",
        )
        // v2: nuovi default (carburante, velocita, spinta, olio); le scelte vecchie si azzerano
        private const val CHIAVE_SCELTI = "parametri_scelti_v2"
        private val DEFAULT_SCELTI = setOf("0C", "0D", "BOOST", "BENZINA", "04", "42", "05", "68")
        /** Ordine sul cruscotto: dati di guida prima, temperature per ultime. */
        private val ORDINE = listOf(
            "0C", "0D", "BOOST", "BENZINA", "49", "0B", "04", "11", "2F", "42", "10", "0E", "44", "06", "07",
            "78", "3C", "05", "5C", "68", "0F", "46", "7C", "33",
        )

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
