package cafe.megane.core

/**
 * Riassunto di una registrazione CAN passiva: per ogni ID quanti frame, a che frequenza,
 * quanti dati diversi e quali byte cambiano. Serve a trovare, nel traffico, il messaggio
 * che porta un dato che l'OBD standard non da (pressione gomme, livello benzina...).
 * Solo ascolto: nessun metodo invia niente sul bus.
 */
class CanRiepilogo {
    private class Voce {
        var n = 0
        var primo = 0L
        var ultimo = 0L
        val diversi = LinkedHashSet<String>()           // fino a MAX_DIVERSI dati distinti
        var ultimoDato: IntArray = IntArray(0)
        val valoriPerByte = Array(8) { HashSet<Int>() } // quanti valori diversi assume ogni byte
    }

    private val voci = HashMap<Int, Voce>()
    private var inizio = 0L
    private var fine = 0L

    @Synchronized
    fun azzera() { voci.clear(); inizio = 0; fine = 0 }

    @Synchronized
    fun osserva(f: CanFrame) {
        if (inizio == 0L) inizio = f.timestampMs
        fine = f.timestampMs
        val v = voci.getOrPut(f.id) { Voce().also { it.primo = f.timestampMs } }
        v.n++
        v.ultimo = f.timestampMs
        v.ultimoDato = f.dati
        if (v.diversi.size < MAX_DIVERSI) v.diversi.add(f.datiHex())
        f.dati.take(8).forEachIndexed { i, b -> if (v.valoriPerByte[i].size < 64) v.valoriPerByte[i].add(b) }
    }

    @Synchronized
    fun totale(): Int = voci.values.sumOf { it.n }

    @Synchronized
    fun quantiId(): Int = voci.size

    /** Prime righe (ID piu attivi) per mostrarle sul display: [titolo, dettaglio]. */
    @Synchronized
    fun righe(max: Int = 20): List<Pair<String, String>> {
        val secondi = ((fine - inizio) / 1000.0).coerceAtLeast(0.001)
        return voci.entries.sortedByDescending { it.value.n }.take(max).map { (id, v) ->
            val variabili = (0 until minOf(8, v.ultimoDato.size)).filter { v.valoriPerByte[it].size > 1 }
            "ID %03X  (%d messaggi, %.1f/s)".format(id, v.n, v.n / secondi) to
                (v.ultimoDato.joinToString(" ") { "%02X".format(it) } +
                    if (variabili.isEmpty()) "  ·  nessun byte cambia" else "  ·  cambiano i byte " + variabili.joinToString(","))
        }
    }

    /** Testo da condividere. Piu frame e piu dati diversi = piu utile. */
    @Synchronized
    fun testo(): String = buildString {
        val secondi = ((fine - inizio) / 1000.0).coerceAtLeast(0.001)
        appendLine("Registrazione CAN passiva: ${voci.size} ID, ${voci.values.sumOf { it.n }} frame in ${"%.0f".format(secondi)} s")
        if (voci.isEmpty()) {
            appendLine("Nessun frame: il bus della presa OBD non trasmette messaggi propri (probabile gateway)")
            return@buildString
        }
        appendLine("ID  n  Hz  dati-diversi  byte-variabili(valori)  ultimo  esempi")
        voci.entries.sortedByDescending { it.value.n }.take(MAX_ID).forEach { (id, v) ->
            val hz = v.n / secondi
            val variabili = (0 until minOf(8, v.ultimoDato.size))
                .filter { v.valoriPerByte[it].size > 1 }
                .joinToString(",") { "$it(${if (v.valoriPerByte[it].size >= 64) "64+" else v.valoriPerByte[it].size})" }
                .ifEmpty { "-" }
            appendLine("%03X %d %.1f %s%d [%s] %s | %s".format(
                id, v.n, hz, if (v.diversi.size >= MAX_DIVERSI) ">=" else "", v.diversi.size, variabili,
                v.ultimoDato.joinToString(" ") { "%02X".format(it) },
                v.diversi.take(3).joinToString(" ; "),
            ))
        }
    }

    companion object {
        private const val MAX_DIVERSI = 40
        private const val MAX_ID = 120
    }
}
