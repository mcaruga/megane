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
        versione = reset.lines().map { it.trim() }
            .lastOrNull { it.contains("ELM", ignoreCase = true) || it.contains("v", ignoreCase = true) }
            ?: ""
        initSequence.forEach { send(it) }
        require(versione.isNotEmpty() || send("ATI", 3_000).isNotEmpty()) {
            "Il dispositivo non risponde come un adattatore OBD (ELM327)"
        }
        versione.ifEmpty { "ELM327" }
    }

    /** La centralina risponde? Prima richiesta dopo ATSP0: la ricerca protocollo e lenta. */
    fun centralinaRisponde(): Boolean {
        val r = send("0100", 12_000)
        return r.isNotEmpty() && !r.contains("UNABLE") && !r.contains("NO DATA") &&
            !r.contains("ERROR") && !r.contains("STOPPED")
    }

    /** Legge un PID. Ritorna i byte grezzi, la decodifica sta in [PidDecoder]. */
    fun readPid(mode: String, pid: String): IntArray? {
        val raw = send(mode + pid)
        if (raw.contains("NO DATA") || raw.contains("ERROR")) return null
        // risposta: mode+0x40, pid, poi i dati -> scarta i primi 2 byte
        return raw.filter { it.isLetterOrDigit() }
            .chunked(2)
            .mapNotNull { it.toIntOrNull(16) }
            .drop(2)
            .toIntArray()
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
        val deadline = System.currentTimeMillis() + attesaMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read().toChar()
                if (c == '>') break
                sb.append(c)
            } else Thread.sleep(5)
        }
        return sb.toString()
            .replace("SEARCHING...", "")
            .replace("BUS INIT: ...OK", "")
            .replace("BUS INIT: OK", "")
            .trim()
    }
}

/**
 * Risponde alla tua richiesta: nessuna lettura a motore spento.
 * L'app non apre nemmeno la connessione se i giri sono a zero,
 * cosi l'adattatore non viene tenuto sveglio dal software.
 */
object EngineGate {
    private const val RPM_MINIMO = 300

    fun motoreAcceso(elm: Elm327): Boolean {
        val rpm = elm.readPid("01", "0C") ?: return false
        return rpm.size >= 2 && ((rpm[0] * 256 + rpm[1]) / 4) > RPM_MINIMO
    }
}
