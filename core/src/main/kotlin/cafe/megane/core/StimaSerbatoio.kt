package cafe.megane.core

/**
 * Livello benzina STIMATO. L'OBD standard della Megane non da il livello del serbatoio,
 * quindi: tu dici quanto ce n'e (o fai il pieno), l'app sottrae il consumo calcolato.
 *
 *  - Consumo: dal flusso carburante (PID 5E se l'auto lo da) oppure dall'aria aspirata (MAF / 14,7).
 *  - Calibrazione: a ogni pieno confronta i litri messi alla pompa con il consumo calcolato
 *    dall'ultimo pieno e corregge il fattore `k`. Dopo 2-3 pieni l'errore scende di molto.
 *  - Errori noti: il MAF non vede il taglio in rilascio e le miscele ricche sotto carico.
 *    Quindi e una stima: guarda comunque la spia dell'auto.
 */
class StimaSerbatoio(
    var capacita: Double = 47.0,
    litri: Double? = null,
    var k: Double = 1.0,
    private var consumoGrezzo: Double = 0.0,
    private var aggiuntiDaPieno: Double = 0.0,
    private var pienoValido: Boolean = false,
) {
    /** Litri stimati nel serbatoio; null finche non dici quanto ce n'e. */
    var litri: Double? = litri
        private set

    @Synchronized
    fun integra(flussoLh: Double, dtS: Double) {
        val l = litri ?: return
        if (flussoLh < 0 || dtS <= 0) return
        val grezzi = flussoLh * dtS / 3600.0
        consumoGrezzo += grezzi
        litri = (l - grezzi * k).coerceAtLeast(0.0)
    }

    /** Pieno fatto: `messi` sono i litri alla pompa. Calibra se possibile. */
    @Synchronized
    fun pieno(messi: Double) {
        if (pienoValido && consumoGrezzo >= CONSUMO_MINIMO_CALIBRAZIONE && messi > 0) {
            val nuovo = ((messi + aggiuntiDaPieno) / consumoGrezzo).coerceIn(K_MIN, K_MAX)
            k = (0.5 * k + 0.5 * nuovo).coerceIn(K_MIN, K_MAX)
        }
        litri = capacita
        consumoGrezzo = 0.0
        aggiuntiDaPieno = 0.0
        pienoValido = true
    }

    /** Rifornimento parziale: aggiunge litri senza riempire. */
    @Synchronized
    fun aggiunti(l: Double) {
        if (l <= 0) return
        litri = ((litri ?: 0.0) + l).coerceAtMost(capacita)
        aggiuntiDaPieno += l
    }

    /** Dici tu quanto ce n'e adesso (letto dall'auto). La calibrazione riparte dal prossimo pieno. */
    @Synchronized
    fun imposta(l: Double) {
        litri = l.coerceIn(0.0, capacita)
        consumoGrezzo = 0.0
        aggiuntiDaPieno = 0.0
        pienoValido = false
    }

    @Synchronized
    fun frazione(): Double? = litri?.let { (it / capacita).coerceIn(0.0, 1.0) }

    @Synchronized
    fun salva(): Map<String, Double> = mapOf(
        "capacita" to capacita, "litri" to (litri ?: -1.0), "k" to k,
        "grezzo" to consumoGrezzo, "aggiunti" to aggiuntiDaPieno, "valido" to if (pienoValido) 1.0 else 0.0,
    )

    companion object {
        const val CONSUMO_MINIMO_CALIBRAZIONE = 5.0
        private const val K_MIN = 0.7
        private const val K_MAX = 1.4

        /** Benzina: aria / 14,7 = grammi di benzina al secondo; densita 745 g/l; in litri all'ora. */
        fun flussoDaMaf(mafGs: Double): Double = mafGs / 14.7 / 745.0 * 3600.0

        fun carica(m: Map<String, Double>): StimaSerbatoio = StimaSerbatoio(
            capacita = m["capacita"] ?: 47.0,
            litri = m["litri"]?.takeIf { it >= 0 },
            k = m["k"] ?: 1.0,
            consumoGrezzo = m["grezzo"] ?: 0.0,
            aggiuntiDaPieno = m["aggiunti"] ?: 0.0,
            pienoValido = (m["valido"] ?: 0.0) > 0.5,
        )
    }
}
