package cafe.megane.core

import java.io.InputStream
import java.io.OutputStream

/**
 * Driver ELM327 in SOLA LETTURA.
 *
 * Vincoli di progetto, volutamente non configurabili:
 *  - Solo mode 01 (dati live), 03/07 (DTC), 09 (VIN). Nessuna scrittura.
 *  - Mode 04 (cancella DTC) NON implementato: cancellare i codici nasconde i guasti.
 *  - Nessun comando UDS raw, nessun 2E/2F/31 (scrittura e routine).
 *  - Legge solo a motore acceso: vedi [EngineGate].
 */
class Elm327(
    private val input: InputStream,
    private val output: OutputStream,
    private val timeoutMs: Long = 2_000,
) {
    /** Versione riportata dall'adattatore dopo ATZ (es. "ELM327 v2.2"). */
    @Volatile var versione: String = ""
        private set

    /** Comandi AT ammessi. Whitelist: niente oltre questi. ATZ va a parte (risposta lenta). */
    private val initSequence = listOf(
        "ATE0",     // echo off
        "ATL0",     // linefeed off
        "ATS0",     // spazi off, meno byte da parsare
        "ATH0",     // header off
        "ATSP0",    // autodetect protocollo
    )

    /**
     * Inizializza l'adattatore. Riesce se l'ELM327 risponde, anche a quadro spento:
     * se la centralina risponde lo decide poi [EngineGate].
     */
    fun connect(): Result<String> = runCatching {
        val reset = send("ATZ", 5_000)
        log.add("ATZ | $ultimiByte")
        versione = reset.lines().map { it.trim() }
            .lastOrNull { it.contains("ELM", ignoreCase = true) || it.contains("v", ignoreCase = true) }
            ?: ""
        initSequence.forEach { send(it); log.add("$it | $ultimiByte") }
        val ati = if (versione.isEmpty()) send("ATI", 3_000) else ""
        require(versione.isNotEmpty() || (ati.isNotEmpty() && ati.all { it.code in 9..126 })) {
            "Il dispositivo risponde con byte illeggibili ($ultimiByte): non e un ELM327 leggibile"
        }
        versione.ifEmpty { "ELM327" }
    }

    /** La centralina risponde? Prima richiesta dopo ATSP0: la ricerca protocollo e lenta. */
    fun centralinaRisponde(): Boolean {
        // risposta valida solo se contiene davvero "41 00": byte spazzatura NON sono una centralina
        for (proto in listOf(null, "ATSP6", "ATSP7")) {
            if (proto != null) send(proto)
            val r = send("0100", 12_000)
            ultimaRisposta = r
            log.add("${proto ?: "ATSP0"} | $ultimiByte")
            if (estraiPid(r, "01", "00") != null) return true
        }
        return false
    }

    /** Ultimo scambio in esadecimale: distingue testo ELM da byte illeggibili. */
    @Volatile var ultimiByte: String = ""
        private set

    /** Scambi dell'ultima connessione, per la diagnosi. */
    val log: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** Ultima risposta grezza: finisce nella diagnosi quando qualcosa non torna. */
    @Volatile var ultimaRisposta: String = ""
        private set

    /**
     * Legge un PID. Ritorna i byte dopo "41 <pid>", la decodifica sta in [PidDecoder].
     * Cerca l'intestazione attesa riga per riga: regge eco, righe spurie e
     * risposte di piu centraline (non basta scartare i primi 2 byte).
     */
    fun readPid(mode: String, pid: String): IntArray? {
        val raw = send(mode + pid)
        ultimaRisposta = raw
        return estraiPid(raw, mode, pid)
    }

    /**
     * Quali PID (mode 01) l'auto sa rispondere: sola lettura, richieste standard 0100, 0120, 0140...
     * Ritorna i PID in esadecimale a due cifre ("0C", "5C"). Vuoto se la centralina non risponde.
     */
    fun pidSupportati(): Set<String> {
        val out = mutableSetOf<String>()
        var base = 0
        while (base <= 0xC0) {
            val dati = readPid("01", "%02X".format(base)) ?: break
            if (dati.size < 4) break
            for (i in 0 until 32) {
                if ((dati[i / 8] shr (7 - i % 8)) and 1 == 1) out += "%02X".format(base + i + 1)
            }
            if ("%02X".format(base + 0x20) !in out) break      // l'ultimo bit dice se esiste il blocco successivo
            base += 0x20
        }
        return out
    }

    /**
     * Unico punto d'ingresso per la lettura delle ALTRE centraline: ogni comando passa dalla
     * [ListaBianca]. Un comando di scrittura, sessione o cancellazione viene rifiutato qui.
     */
    fun comandoLettura(cmd: String, attesaMs: Long = 1_500): String {
        require(ListaBianca.ammesso(cmd)) { "Comando non ammesso (solo lettura): $cmd" }
        return send(cmd.trim().uppercase().replace(" ", ""), attesaMs)
    }

    /** Codici guasto attivi (mode 03) e pendenti (mode 07). */
    fun readDtc(): List<String> = buildList {
        addAll(parseDtc(send("03")))
        addAll(parseDtc(send("07")))
    }.distinct()

    fun readVin(): String? = send("0902")
        .filter { it.isLetterOrDigit() }
        .chunked(2)
        .mapNotNull { it.toIntOrNull(16) }
        .filter { it in 32..126 }
        .map { it.toChar() }
        .joinToString("")
        .takeIf { it.length >= 17 }
        ?.takeLast(17)

    private fun parseDtc(raw: String): List<String> {
        val bytes = raw.filter { it.isLetterOrDigit() }.chunked(2)
            .mapNotNull { it.toIntOrNull(16) }
        return bytes.drop(1).chunked(2)
            .filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
            .map { (hi, lo) ->
                val prefix = when (hi shr 6) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
                "%s%d%03X".format(prefix, (hi shr 4) and 0x03, ((hi and 0x0F) shl 8) or lo)
            }
    }

    private fun send(cmd: String, attesaMs: Long = timeoutMs): String {
        // butta via avanzi di risposte arrivate in ritardo: senza, le risposte si sfasano
        while (input.available() > 0) input.read()
        output.write("$cmd\r".toByteArray())
        output.flush()
        val sb = StringBuilder()
        val hex = StringBuilder()
        val deadline = System.currentTimeMillis() + attesaMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val b = input.read()
                if (hex.length < 120) hex.append("%02X ".format(b and 0xFF))
                val c = b.toChar()
                if (c == '>') break
                sb.append(c)
            } else Thread.sleep(5)
        }
        ultimiByte = "$cmd -> ${hex.toString().trim()}"
        return sb.toString()
            .replace("SEARCHING...", "")
            .replace("BUS INIT: ...OK", "")
            .replace("BUS INIT: OK", "")
            .trim()
    }
}

/** Byte dati dopo "4x <pid>" nella risposta grezza, o null. Pura: testabile. */
fun estraiPid(raw: String, mode: String, pid: String): IntArray? {
    val errori = listOf("NO DATA", "ERROR", "UNABLE", "STOPPED", "?")
    if (raw.isBlank() || errori.any { raw.contains(it) }) return null
    val atteso = "%02X".format(mode.toInt(16) + 0x40) + pid.uppercase()
    for (riga in raw.lines()) {
        val hex = riga.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.any { it !in "0123456789ABCDEF" }) continue
        var i = 0
        while (i + atteso.length <= hex.length) {
            if (hex.startsWith(atteso, i)) {
                val dati = hex.substring(i + atteso.length).chunked(2)
                    .filter { it.length == 2 }.map { it.toInt(16) }
                if (dati.isNotEmpty()) return dati.toIntArray()
            }
            i += 2
        }
    }
    return null
}

/**
 * Risponde alla tua richiesta: nessuna lettura a motore spento.
 * L'app non apre nemmeno la connessione se i giri sono a zero,
 * cosi l'adattatore non viene tenuto sveglio dal software.
 */
object EngineGate {
    private const val RPM_MINIMO = 300

    fun motoreAcceso(elm: Elm327): Boolean = (giri(elm) ?: 0) > RPM_MINIMO

    /** Giri motore, o null se la centralina non risponde (quadro spento). */
    fun giri(elm: Elm327): Int? {
        val rpm = elm.readPid("01", "0C") ?: return null
        return if (rpm.size >= 2) (rpm[0] * 256 + rpm[1]) / 4 else null
    }

    fun accesoDaGiri(giri: Int) = giri > RPM_MINIMO
}
