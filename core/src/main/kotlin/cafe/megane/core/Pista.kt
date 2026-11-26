package cafe.megane.core

import kotlin.math.cos
import kotlin.math.hypot

/** Giro completato. */
data class Giro(val numero: Int, val tempoMs: Long)

/** Punto proiettato in metri rispetto al primo punto GPS della sessione. */
data class PuntoPista(val x: Double, val y: Double)

/**
 * Cronometro da pista con la posizione GPS del telefono. Pura logica: niente Android.
 *
 *  1. `fix()` riceve le posizioni (lat, lon, istante).
 *  2. `impostaTraguardo()` mette la linea del traguardo dove sei adesso, perpendicolare
 *     alla direzione di marcia.
 *  3. Ogni volta che il segmento tra due posizioni taglia la linea, il giro e chiuso.
 *     L'istante e interpolato tra le due posizioni, cosi non conta la distanza tra i fix.
 *
 * Precisione: GPS del telefono, qualche decimo di secondo. Non e un trasponder.
 * Il disegno del circuito e la traccia del miglior giro (o del giro in corso).
 */
class SessionePista {
    private var lat0 = Double.NaN
    private var lon0 = 0.0
    private var mPerGradoLon = 111_320.0

    private class Campione(val x: Double, val y: Double, val tMs: Long, val distM: Double)

    private val recenti = ArrayList<Campione>()          // tutto da quando la sessione e attiva
    private var traguardoA: PuntoPista? = null
    private var traguardoB: PuntoPista? = null
    private var dirX = 0.0
    private var dirY = 0.0

    private var inizioGiroMs: Long? = null
    private var giroCorrente = ArrayList<Campione>()
    private var migliorTraccia: List<Campione> = emptyList()

    private val _giri = ArrayList<Giro>()
    val giri: List<Giro> get() = _giri
    var migliore: Giro? = null; private set
    val ultimo: Giro? get() = _giri.lastOrNull()
    val haTraguardo: Boolean get() = traguardoA != null
    val inGiro: Boolean get() = inizioGiroMs != null

    @Synchronized
    fun azzera() {
        lat0 = Double.NaN
        recenti.clear(); giroCorrente = ArrayList(); migliorTraccia = emptyList()
        traguardoA = null; traguardoB = null; inizioGiroMs = null
        _giri.clear(); migliore = null
    }

    private fun proietta(lat: Double, lon: Double): PuntoPista {
        if (lat0.isNaN()) { lat0 = lat; lon0 = lon; mPerGradoLon = 111_320.0 * cos(Math.toRadians(lat)) }
        return PuntoPista((lon - lon0) * mPerGradoLon, (lat - lat0) * 111_320.0)
    }

    /** Un nuovo punto GPS. Ritorna il giro se questo punto ne chiude uno. */
    @Synchronized
    fun fix(lat: Double, lon: Double, tMs: Long): Giro? {
        val p = proietta(lat, lon)
        val prec = recenti.lastOrNull()
        val dist = (prec?.distM ?: 0.0) + (prec?.let { hypot(p.x - it.x, p.y - it.y) } ?: 0.0)
        val c = Campione(p.x, p.y, tMs, dist)
        recenti.add(c)
        if (recenti.size > MAX_PUNTI) recenti.removeAt(0)

        var chiuso: Giro? = null
        val a = traguardoA; val b = traguardoB
        if (prec != null && a != null && b != null) {
            val cross = incrocio(prec, c, a, b)
            if (cross != null) {
                val tCross = cross.tMs
                val inizio = inizioGiroMs
                if (inizio != null && tCross - inizio >= GIRO_MINIMO_MS) {
                    val g = Giro(_giri.size + 1, tCross - inizio)
                    _giri.add(g)
                    if (migliore == null || g.tempoMs < migliore!!.tempoMs) {
                        migliore = g
                        migliorTraccia = ArrayList(giroCorrente).also { it.add(cross) }
                    }
                    chiuso = g
                }
                if (inizio == null || chiuso != null) {
                    inizioGiroMs = tCross
                    // il punto di partenza e l'incrocio interpolato, uguale in ogni giro:
                    // cosi distanze e tempi dei giri sono confrontabili per il distacco
                    giroCorrente = arrayListOf(cross)
                }
            }
        }
        if (inizioGiroMs != null) giroCorrente.add(c)
        return chiuso
    }

    /** Mette il traguardo nel punto attuale, perpendicolare alla direzione di marcia. false se mancano dati. */
    @Synchronized
    fun impostaTraguardo(): Boolean {
        if (recenti.size < 2) return false
        val c = recenti.last()
        // direzione: dal punto di almeno 5 m fa, per non usare due fix quasi uguali da fermo
        val prima = recenti.asReversed().firstOrNull { hypot(c.x - it.x, c.y - it.y) >= 5.0 } ?: return false
        val dx = c.x - prima.x; val dy = c.y - prima.y
        val n = hypot(dx, dy)
        dirX = dx / n; dirY = dy / n
        // la linea e perpendicolare: (-dirY, dirX), larga LARGHEZZA_M per lato
        traguardoA = PuntoPista(c.x - dirY * LARGHEZZA_M, c.y + dirX * LARGHEZZA_M)
        traguardoB = PuntoPista(c.x + dirY * LARGHEZZA_M, c.y - dirX * LARGHEZZA_M)
        _giri.clear(); migliore = null; migliorTraccia = emptyList()
        inizioGiroMs = null          // il primo passaggio sulla linea fa partire il cronometro
        giroCorrente = ArrayList()
        return true
    }

    /** Tempo del giro in corso, o null se il cronometro non e partito. */
    @Synchronized
    fun tempoGiro(oraMs: Long): Long? = inizioGiroMs?.let { oraMs - it }

    /**
     * Distacco dal miglior giro: tempo trascorso nel giro in corso meno il tempo che il miglior giro
     * aveva alla stessa distanza percorsa. Negativo = sei in vantaggio.
     */
    @Synchronized
    fun delta(): Long? {
        val inizio = inizioGiroMs ?: return null
        val ora = giroCorrente.lastOrNull() ?: return null
        val rif = migliorTraccia
        if (rif.size < 2 || giroCorrente.isEmpty()) return null
        val d = ora.distM - giroCorrente.first().distM          // distanza percorsa in questo giro
        val base = rif.first().distM
        val dr = d + base
        if (dr > rif.last().distM) return null
        val i = rif.indexOfFirst { it.distM >= dr }.takeIf { it > 0 } ?: return null
        val a = rif[i - 1]; val b = rif[i]
        val f = if (b.distM > a.distM) (dr - a.distM) / (b.distM - a.distM) else 0.0
        val tRif = (a.tMs + f * (b.tMs - a.tMs)) - rif.first().tMs
        return ((ora.tMs - inizio) - tRif).toLong()
    }

    /** Disegno del circuito: miglior giro, altrimenti giro in corso, altrimenti tutto cio che e stato registrato. */
    @Synchronized
    fun circuito(): List<PuntoPista> {
        val sorgente: List<Campione> = when {
            migliorTraccia.size >= 5 -> migliorTraccia
            giroCorrente.size >= 5 -> giroCorrente
            else -> recenti
        }
        return sorgente.map { PuntoPista(it.x, it.y) }
    }

    @Synchronized fun posizione(): PuntoPista? = recenti.lastOrNull()?.let { PuntoPista(it.x, it.y) }
    @Synchronized fun linea(): Pair<PuntoPista, PuntoPista>? =
        traguardoA?.let { a -> traguardoB?.let { b -> a to b } }

    /** Punto in cui il segmento p->q taglia la linea del traguardo nel verso di marcia; null se non la taglia. */
    private fun incrocio(p: Campione, q: Campione, a: PuntoPista, b: PuntoPista): Campione? {
        val rx = q.x - p.x; val ry = q.y - p.y
        if (rx * dirX + ry * dirY <= 0) return null                      // verso opposto
        val sx = b.x - a.x; val sy = b.y - a.y
        val den = rx * sy - ry * sx
        if (den == 0.0) return null
        val qpx = a.x - p.x; val qpy = a.y - p.y
        val t = (qpx * sy - qpy * sx) / den
        val u = (qpx * ry - qpy * rx) / den
        // t < 1e-6: un segmento che PARTE sulla linea non conta, conta quello che ci arriva
        if (t < 1e-6 || t > 1.0 + 1e-9 || u < 0.0 || u > 1.0) return null
        return Campione(
            p.x + t * rx, p.y + t * ry,
            p.tMs + (t * (q.tMs - p.tMs)).toLong(),
            p.distM + t * (q.distM - p.distM),
        )
    }

    companion object {
        const val GIRO_MINIMO_MS = 15_000L
        private const val LARGHEZZA_M = 30.0
        private const val MAX_PUNTI = 20_000

        /** "1:23.456" oppure "23.456". */
        fun formatta(ms: Long): String {
            val neg = ms < 0; val v = kotlin.math.abs(ms)
            val m = v / 60_000; val s = (v % 60_000) / 1000.0
            val t = if (m > 0) "%d:%06.3f".format(m, s) else "%.3f".format(s)
            return if (neg) "-$t" else t
        }
    }
}
