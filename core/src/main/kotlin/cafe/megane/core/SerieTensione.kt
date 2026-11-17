package cafe.megane.core

import kotlin.math.roundToInt

/**
 * Analisi della serie storica di tensione 12V, logica pura e testabile.
 * Nessuna dipendenza Android: prende i valori, restituisce statistiche,
 * punti ridotti per il grafico e uno sparkline ASCII.
 *
 * Il punto diagnostico non e la media ma i CALI: se con batteria "carica"
 * la tensione crolla sotto soglia in certi istanti, quei transitori possono
 * spiegare Sport mode e sound design a singhiozzo.
 */
class SerieTensione(private val valori: List<Double>) {

    val vuota: Boolean get() = valori.isEmpty()
    val letture: Int get() = valori.size
    val minimo: Double get() = valori.minOrNull() ?: 0.0
    val massimo: Double get() = valori.maxOrNull() ?: 0.0
    val media: Double get() = if (valori.isEmpty()) 0.0 else valori.average()

    fun cali(soglia: Double = SOGLIA_CALO): Int = valori.count { it < soglia }

    /**
     * Riduce la serie a [larghezza] punti facendo la media di ogni blocco.
     * Serve a disegnare un grafico leggibile anche con migliaia di letture.
     */
    fun campiona(larghezza: Int): List<Double> {
        if (valori.isEmpty() || larghezza <= 0) return emptyList()
        if (valori.size <= larghezza) return valori
        val blocco = valori.size.toDouble() / larghezza
        return (0 until larghezza).map { i ->
            val da = (i * blocco).toInt()
            val a = ((i + 1) * blocco).toInt().coerceAtMost(valori.size)
            valori.subList(da, a.coerceAtLeast(da + 1)).average()
        }
    }

    /**
     * Normalizza i valori campionati in altezze 0..altezza, per il disegno.
     * La scala e fissata su un range utile (11.0-15.0 V) cosi grafici diversi
     * sono confrontabili e un calo si vede davvero, non schiacciato.
     */
    fun altezzeGrafico(larghezza: Int, altezza: Int): List<Int> {
        val punti = campiona(larghezza)
        return punti.map { v ->
            val norm = ((v - SCALA_MIN) / (SCALA_MAX - SCALA_MIN)).coerceIn(0.0, 1.0)
            (norm * altezza).roundToInt()
        }
    }

    /** Sparkline ASCII: funziona anche nei template Android Auto (solo testo). */
    fun sparkline(larghezza: Int = 24): String {
        if (valori.isEmpty()) return ""
        val livelli = "_.-=*#"
        return campiona(larghezza).joinToString("") { v ->
            val norm = ((v - SCALA_MIN) / (SCALA_MAX - SCALA_MIN)).coerceIn(0.0, 1.0)
            val idx = (norm * (livelli.length - 1)).roundToInt()
            livelli[idx].toString()
        }
    }

    /** Verdetto diagnostico in italiano, stesso criterio di prima ma testabile. */
    fun verdetto(soglia: Double = SOGLIA_CALO): String {
        if (valori.isEmpty()) return "Nessun dato ancora registrato."
        val n = cali(soglia)
        return when {
            n == 0 && massimo in 13.6..14.8 ->
                "Alimentazione sana. L'intermittenza NON dipende dalla 12V: " +
                    "resta un problema di configurazione software."
            massimo < 13.4 ->
                "A motore acceso non si vedono 13.8-14.6 V. Sospetto alternatore o regolatore."
            else ->
                "Rilevati $n cali sotto $soglia V. Anche con batteria apparentemente carica, " +
                    "questi transitori possono spiegare le funzioni a singhiozzo."
        }
    }

    fun riassunto(soglia: Double = SOGLIA_CALO): String {
        if (valori.isEmpty()) return "Nessun dato ancora registrato."
        return buildString {
            appendLine("Letture: $letture")
            appendLine("Minimo:  %.2f V".format(minimo))
            appendLine("Massimo: %.2f V".format(massimo))
            appendLine("Media:   %.2f V".format(media))
            appendLine("Cali sotto $soglia V: ${cali(soglia)}")
            appendLine()
            append(verdetto(soglia))
        }
    }

    companion object {
        const val SOGLIA_CALO = 12.2
        const val SCALA_MIN = 11.0
        const val SCALA_MAX = 15.0

        /** Estrae i valori di tensione dalle righe del CSV "data;volt;nota". */
        fun daCsv(righe: List<String>): SerieTensione =
            SerieTensione(
                righe.drop(1).mapNotNull { r ->
                    r.split(";").getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
                }
            )
    }
}
