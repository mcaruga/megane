package cafe.megane.core

/**
 * Correlatore passivo di eventi CAN.
 *
 * Questo e lo strumento per il tuo caso reale: Sport mode che va a
 * intermittenza con batteria sana. Metodo, non magia:
 *
 *  1. Avvii il logger passivo.
 *  2. Quando premi Sport e FUNZIONA -> marchi "ok".
 *  3. Quando premi Sport e NON funziona -> marchi "ko".
 *  4. Ripeti qualche volta.
 *  5. Il correlatore confronta i frame nei due casi e isola gli ID
 *     il cui contenuto cambia in modo coerente tra ok e ko.
 *
 * Non invia niente. Guarda soltanto. Ti dice QUALE centralina e QUALE
 * byte si comporta diverso: da li si capisce se e un modulo che non
 * risponde, non se "manca una funzione RS".
 */
class CanCorrelatore {

    enum class Esito { OK, KO }

    private data class Campione(val esito: Esito, val frame: Map<Int, IntArray>)

    private val campioni = mutableListOf<Campione>()
    private val buffer = LinkedHashMap<Int, IntArray>()

    /** Chiamato dal logger per ogni frame in arrivo. */
    fun osserva(frame: CanFrame) {
        buffer[frame.id] = frame.dati
    }

    /** L'utente marca l'istante: snapshot dello stato corrente del bus. */
    fun marca(esito: Esito) {
        campioni.add(Campione(esito, HashMap(buffer)))
    }

    /**
     * Confronta i campioni OK contro i KO e ritorna gli ID sospetti:
     * quelli il cui valore e sistematicamente diverso tra i due gruppi.
     */
    fun analizza(): List<Sospetto> {
        val ok = campioni.filter { it.esito == Esito.OK }
        val ko = campioni.filter { it.esito == Esito.KO }
        if (ok.isEmpty() || ko.isEmpty()) return emptyList()

        val idComuni = ok.flatMap { it.frame.keys }.toSet()
            .intersect(ko.flatMap { it.frame.keys }.toSet())

        return idComuni.mapNotNull { id ->
            val valoriOk = ok.mapNotNull { it.frame[id]?.toList() }.toSet()
            val valoriKo = ko.mapNotNull { it.frame[id]?.toList() }.toSet()
            // sospetto se i due insiemi non si sovrappongono: cambia sempre
            if (valoriOk.isNotEmpty() && valoriKo.isNotEmpty() &&
                valoriOk.intersect(valoriKo).isEmpty()
            ) {
                Sospetto(
                    id = id,
                    byteDiversi = byteCheCambiano(valoriOk.first(), valoriKo.first()),
                    esempioOk = valoriOk.first().joinToString(" ") { "%02X".format(it) },
                    esempioKo = valoriKo.first().joinToString(" ") { "%02X".format(it) },
                )
            } else null
        }.sortedBy { it.id }
    }

    private fun byteCheCambiano(a: List<Int>, b: List<Int>): List<Int> =
        (0 until minOf(a.size, b.size)).filter { a[it] != b[it] }

    fun reset() { campioni.clear(); buffer.clear() }

    fun statoRaccolta(): String {
        val ok = campioni.count { it.esito == Esito.OK }
        val ko = campioni.count { it.esito == Esito.KO }
        return "Campioni OK: $ok, KO: $ko. " +
            if (ok >= 2 && ko >= 2) "Pronto per l'analisi."
            else "Servono almeno 2 OK e 2 KO per un risultato affidabile."
    }

    data class Sospetto(
        val id: Int,
        val byteDiversi: List<Int>,
        val esempioOk: String,
        val esempioKo: String,
    ) {
        fun descrizione(): String =
            "ID %03X - byte diversi: %s\n  quando OK: %s\n  quando KO: %s".format(
                id, byteDiversi.joinToString(","), esempioOk, esempioKo,
            )
    }

    /**
     * Esporta i frame sospetti in CSV. Da portare come prova all'officina:
     * mostra nero su bianco quale centralina risponde diverso tra OK e KO.
     * Separatore ';' e virgola decimale assenti: si apre pulito in Excel italiano.
     */
    fun esportaCsv(): String = buildString {
        appendLine("id_can;byte_diversi;valori_ok;valori_ko;campioni_ok;campioni_ko")
        val ok = campioni.count { it.esito == Esito.OK }
        val ko = campioni.count { it.esito == Esito.KO }
        analizza().forEach { s ->
            appendLine(
                "%03X;%s;%s;%s;%d;%d".format(
                    s.id,
                    s.byteDiversi.joinToString("|"),
                    s.esempioOk.replace(" ", "|"),
                    s.esempioKo.replace(" ", "|"),
                    ok, ko,
                )
            )
        }
    }
}
