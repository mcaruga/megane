package cafe.megane.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * LISTA BIANCA di tutto cio che la lettura delle centraline puo mandare all'adattatore.
 * Solo lettura: dati di identificazione (22 F180-F19F) e codici guasto (19 01 / 19 02).
 * Tutto il resto e rifiutato prima di arrivare al bus: scrittura (2E, 2F), routine (31),
 * sessioni (10), sicurezza (27), reset (11), cancella DTC (14, 04), trasferimenti (34-37), ecc.
 */
object ListaBianca {
    private val AT = Regex(
        "^AT(SH[0-9A-F]{3}|CRA[0-9A-F]{3}|CF[0-9A-F]{3}|CM[0-9A-F]{3}|FCSH[0-9A-F]{3}|FCSM[01]|AR|" +
            "ST[0-9A-F]{1,2}|AT[012]|H[01]|CAF[01]|E[01]|L[01]|S[01]|SP[0-9A]|DP|D)$"
    )
    private val HEX = Regex("^[0-9A-F]{2,16}$")

    /** Header (ID di richiesta) ammessi: diagnostica 0x700-0x7DE e 0x7E0-0x7E7. Mai 0x7DF ne le risposte 0x7E8+. */
    fun headerAmmesso(id: Int) = id in 0x700..0x7DE || id in 0x7E0..0x7E7

    fun ammesso(cmd: String): Boolean {
        val c = cmd.trim().uppercase().replace(" ", "")
        if (AT.matches(c)) {
            if (c.startsWith("ATSH") || c.startsWith("ATFCSH")) {
                val id = c.takeLast(3).toInt(16)
                return headerAmmesso(id)
            }
            return true
        }
        if (!HEX.matches(c) || c.length % 2 != 0) return false
        val b = c.chunked(2).map { it.toInt(16) }
        return when (b[0]) {
            0x01, 0x09 -> b.size == 2                                      // OBD: un PID, sola lettura
            0x03, 0x07 -> b.size == 1                                      // OBD: codici guasto
            0x22 -> b.size == 3 && ((b[1] shl 8) or b[2]) in 0xF180..0xF19F // UDS: solo identificazione
            0x19 -> b.size == 3 && b[1] in 1..2                            // UDS: leggi codici guasto
            else -> false
        }
    }
}

@Serializable
data class DtcLetto(val codice: String, val ftb: Int, val stato: Int) {
    /** Stato in italiano, dai bit standard ISO 14229. */
    fun statoTesto(): String = when {
        stato and 0x01 != 0 -> "attivo"
        stato and 0x04 != 0 -> "in attesa di conferma"
        stato and 0x08 != 0 -> "memorizzato (ora non presente)"
        else -> "stato %02X".format(stato)
    }
}

@Serializable
data class Centralina(
    val richiesta: Int,
    val risposta: Int,
    val ident: Map<String, String> = emptyMap(),
    val dtc: List<DtcLetto>? = null,
    val nota: String = "",
) {
    fun nome(): String = ident["F197"] ?: ident["F187"] ?: "Centralina %03X".format(richiesta)
}

@Serializable
data class Scansione(val quando: String, val centraline: List<Centralina>)

object ScansioneJson {
    private val json = Json { ignoreUnknownKeys = true }
    fun a(s: Scansione): String = json.encodeToString(s)
    fun da(t: String): Scansione? = runCatching { json.decodeFromString<Scansione>(t) }.getOrNull()
}

/** Interpretazione delle risposte UDS (pura, testabile). */
object Uds {
    sealed interface Esito {
        class Ok(val dati: IntArray) : Esito
        class Negativo(val servizio: Int, val nrc: Int) : Esito
        object Niente : Esito
    }

    private val ERRORI = listOf("NO DATA", "ERROR", "UNABLE", "STOPPED", "BUFFER", "?", "SEARCHING")

    /** Byte della risposta, senza intestazioni ne indici dei frame multipli. Vuoto se non c'e risposta. */
    fun bytes(raw: String): IntArray {
        val righe = raw.lines().map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (righe.isEmpty() || righe.any { r -> ERRORI.any { r.contains(it) } }) return IntArray(0)
        val multi = righe.any { Regex("^[0-9A-F]:").containsMatchIn(it) }
        val hex = if (multi) righe.filter { it.contains(':') }.joinToString("") { it.substringAfter(':') }
                  else righe.first()
        val pulito = hex.filter { it in "0123456789ABCDEF" }
        return pulito.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }.toIntArray()
    }

    fun interpreta(raw: String): Esito {
        val b = bytes(raw)
        if (b.isEmpty()) return Esito.Niente
        if (b[0] == 0x7F && b.size >= 3) return Esito.Negativo(b[1], b[2])
        return Esito.Ok(b)
    }

    fun nomeNrc(nrc: Int) = when (nrc) {
        0x11 -> "servizio non supportato"
        0x12 -> "sotto-funzione non supportata"
        0x13 -> "formato errato"
        0x22 -> "condizioni non idonee"
        0x31 -> "dato non presente"
        0x33 -> "accesso negato (sicurezza)"
        0x78 -> "risposta in ritardo"
        0x7E, 0x7F -> "non in questa sessione"
        else -> "codice %02X".format(nrc)
    }

    /** Testo: ASCII se tutti i byte sono stampabili, altrimenti esadecimale. */
    fun testo(dati: IntArray): String {
        val d = dati.toList().dropLastWhile { it == 0 || it == 0xFF }
        if (d.isEmpty()) return "-"
        return if (d.all { it in 32..126 }) d.map { it.toChar() }.joinToString("")
        else d.joinToString(" ") { "%02X".format(it) }
    }

    private fun prefisso(hi: Int) = when (hi shr 6) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }

    /** Elenco codici guasto dopo `59 02 <maschera>`: record da 4 byte [alto, medio, tipo, stato]. */
    fun dtc(b: IntArray): List<DtcLetto> {
        if (b.size < 3 || b[0] != 0x59 || b[1] != 0x02) return emptyList()
        val out = ArrayList<DtcLetto>()
        var i = 3
        while (i + 3 < b.size) {
            val hi = b[i]; val mid = b[i + 1]; val ftb = b[i + 2]; val st = b[i + 3]
            if (hi != 0 || mid != 0 || ftb != 0) {
                val codice = "%s%d%03X".format(prefisso(hi), (hi shr 4) and 0x03, ((hi and 0x0F) shl 8) or mid)
                out += DtcLetto(codice, ftb, st)
            }
            i += 4
        }
        return out
    }
}

/**
 * Scansiona le centraline in SOLA LETTURA: per ogni ID diagnostico chiede il numero di telaio (22 F190);
 * chi risponde, anche con un rifiuto, e una centralina. Poi legge i suoi dati di identificazione
 * e i codici guasto. Passa solo da [Elm327.comandoLettura], cioe dalla [ListaBianca].
 */
class LetturaCentraline(
    private val elm: Elm327,
    private val progresso: (String) -> Unit = {},
    private val fermati: () -> Boolean = { false },
) {
    companion object {
        val IDENT = linkedMapOf(
            0xF190 to "Telaio (VIN)", 0xF197 to "Nome sistema", 0xF187 to "Codice ricambio",
            0xF18C to "Numero di serie", 0xF191 to "Versione hardware", 0xF194 to "Software (numero)",
            0xF195 to "Software (versione)", 0xF18A to "Fornitore",
        )
        val CANDIDATI: List<Int> = (0x700..0x7DE).toList()
    }

    fun scansiona(): List<Centralina> {
        // configura: niente echo, intestazioni visibili, accetta risposte da qualsiasi ID
        listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATCAF1", "ATAT0", "ATST28", "ATCM000", "ATCF000").forEach {
            elm.comandoLettura(it, 600)
        }
        val trovate = ArrayList<Pair<Int, Int>>()
        for ((n, id) in CANDIDATI.withIndex()) {
            if (fermati()) break
            if (n % 8 == 0) progresso("Cerco centraline... %d%%  (trovate %d)".format(n * 100 / CANDIDATI.size, trovate.size))
            elm.comandoLettura("ATSH%03X".format(id), 400)
            elm.comandoLettura("ATFCSH%03X".format(id), 400)
            val raw = elm.comandoLettura("22F190", 900)
            val risposta = rispostaId(raw) ?: continue
            if (risposta != id) trovate += id to risposta
        }
        val out = ArrayList<Centralina>()
        for ((k, p) in trovate.withIndex()) {
            if (fermati()) break
            val (req, resp) = p
            progresso("Leggo la centralina %03X (%d di %d)...".format(req, k + 1, trovate.size))
            out += leggi(req, resp)
        }
        progresso("Finito: %d centraline".format(out.size))
        return out
    }

    /** ID da cui e arrivata una risposta valida (prime tre cifre con intestazioni attive); null se niente. */
    private fun rispostaId(raw: String): Int? {
        val riga = raw.lines().map { it.trim().uppercase() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (listOf("NO DATA", "ERROR", "UNABLE", "STOPPED", "BUFFER", "?", "SEARCHING").any { riga.contains(it) }) return null
        if (riga.length < 5 || riga.any { it !in "0123456789ABCDEF" }) return null
        return riga.take(3).toIntOrNull(16)
    }

    private fun leggi(req: Int, resp: Int): Centralina {
        listOf("ATSH%03X".format(req), "ATFCSH%03X".format(req), "ATCRA%03X".format(resp), "ATH0")
            .forEach { elm.comandoLettura(it, 400) }
        val ident = LinkedHashMap<String, String>()
        var nota = ""
        for ((did, nome) in IDENT) {
            val raw = elm.comandoLettura("22%04X".format(did), 1500)
            when (val e = Uds.interpreta(raw)) {
                is Uds.Esito.Ok -> {
                    val b = e.dati
                    if (b.size > 3 && b[0] == 0x62 && b[1] == (did shr 8) && b[2] == (did and 0xFF))
                        ident["%04X".format(did)] = Uds.testo(b.copyOfRange(3, b.size))
                }
                is Uds.Esito.Negativo -> if (did == 0xF190 && e.nrc == 0x33) nota = "accesso negato"
                Uds.Esito.Niente -> {}
            }
        }
        var dtc: List<DtcLetto>? = null
        when (val e = Uds.interpreta(elm.comandoLettura("1902FF", 2500))) {
            is Uds.Esito.Ok -> dtc = Uds.dtc(e.dati).takeIf { e.dati.size >= 3 && e.dati[0] == 0x59 }
            is Uds.Esito.Negativo -> nota = (nota + " guasti: " + Uds.nomeNrc(e.nrc)).trim()
            Uds.Esito.Niente -> nota = (nota + " guasti: nessuna risposta").trim()
        }
        // dopo la lettura riporta il filtro a tutto
        elm.comandoLettura("ATH1", 300)
        elm.comandoLettura("ATCM000", 300)
        elm.comandoLettura("ATCF000", 300)
        return Centralina(req, resp, ident, dtc, nota)
    }
}
