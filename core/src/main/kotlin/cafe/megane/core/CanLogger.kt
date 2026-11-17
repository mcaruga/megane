package cafe.megane.core

import java.io.InputStream
import java.io.OutputStream

/**
 * Logger CAN PASSIVO. Ascolta il bus, non trasmette mai.
 *
 * Due sorgenti:
 *  - ELM327 via comando ATMA (monitor all): semplice, ma puo perdere frame
 *    sotto traffico intenso. Va bene per correlare eventi lenti (Sport mode).
 *  - SLCAN (canalyze, CANable, ecc): interfaccia CAN vera, cattura completa.
 *
 * Nessun metodo di questa classe scrive sul bus. Non esiste un send() qui:
 * e una scelta di progetto, non una dimenticanza. Per iniettare frame
 * servirebbe un'altra classe che NON costruiamo.
 */
data class CanFrame(
    val timestampMs: Long,
    val id: Int,
    val dati: IntArray,
) {
    fun idHex(): String = "%03X".format(id)
    fun datiHex(): String = dati.joinToString(" ") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean =
        other is CanFrame && id == other.id && dati.contentEquals(other.dati)
    override fun hashCode(): Int = 31 * id + dati.contentHashCode()
}

interface SorgenteCan {
    /** Avvia l'ascolto. Il callback riceve ogni frame. Bloccante fino a stop(). */
    fun ascolta(onFrame: (CanFrame) -> Unit)
    fun stop()
}

/**
 * Ascolto passivo tramite ELM327 in modalita monitor.
 * @param idleTimeoutMs se non arriva nessun byte per questo tempo, l'ascolto
 *        termina da solo. Serve a non restare bloccati quando il bus dorme
 *        (motore spento): su un bus vivo i frame arrivano in continuazione.
 */
class ElmMonitor(
    private val input: InputStream,
    private val output: OutputStream,
    private val idleTimeoutMs: Long = 3_000,
) : SorgenteCan {

    @Volatile private var attivo = false

    override fun ascolta(onFrame: (CanFrame) -> Unit) {
        // ATMA = Monitor All. Mette l'ELM in sola ricezione: NON invia nulla sul bus.
        invia("ATH1")   // header on: servono gli ID
        invia("ATS0")
        output.write("ATMA\r".toByteArray()); output.flush()
        attivo = true

        val riga = StringBuilder()
        var ultimoByte = System.currentTimeMillis()
        while (attivo) {
            if (input.available() > 0) {
                ultimoByte = System.currentTimeMillis()
                val c = input.read().toChar()
                if (c == '\r' || c == '\n') {
                    parse(riga.toString().trim())?.let(onFrame)
                    riga.clear()
                } else if (c == '>') {
                    break
                } else riga.append(c)
            } else {
                if (System.currentTimeMillis() - ultimoByte > idleTimeoutMs) break
                Thread.sleep(2)
            }
        }
    }

    override fun stop() {
        attivo = false
        // un carattere qualsiasi interrompe ATMA. NON e un comando CAN.
        runCatching { output.write("\r".toByteArray()); output.flush() }
    }

    private fun parse(riga: String): CanFrame? {
        val pulita = riga.filter { it.isLetterOrDigit() }
        if (pulita.length < 3) return null
        val id = pulita.take(3).toIntOrNull(16) ?: return null
        val dati = pulita.drop(3).chunked(2).mapNotNull { it.toIntOrNull(16) }
        return CanFrame(System.currentTimeMillis(), id, dati.toIntArray())
    }

    private fun invia(at: String) {
        output.write("$at\r".toByteArray()); output.flush()
        // Consuma la risposta FINO al prompt '>' dell'ELM, non oltre:
        // svuotare tutto l'input mangerebbe anche i frame ATMA successivi.
        val deadline = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                if (input.read().toChar() == '>') break
            } else Thread.sleep(2)
        }
    }
}

/**
 * Ascolto da device SLCAN (canalyze, CANable). Formato Lawicel.
 * Apriamo il canale in modalita LISTEN-ONLY: 'L' invece di 'O'.
 * In listen-only l'hardware NON mette ACK sul bus: ascolto totalmente invisibile.
 */
class SlcanMonitor(
    private val input: InputStream,
    private val output: OutputStream,
    private val bitrate: Bitrate = Bitrate.B500K,
) : SorgenteCan {

    enum class Bitrate(val codice: String) { B250K("S5"), B500K("S6"), B1M("S8") }

    @Volatile private var attivo = false

    override fun ascolta(onFrame: (CanFrame) -> Unit) {
        comando("C")              // chiudi eventuale canale aperto
        comando(bitrate.codice)   // imposta bitrate
        comando("L")              // APRI IN LISTEN-ONLY: nessun ACK, nessuna TX
        attivo = true

        val riga = StringBuilder()
        while (attivo) {
            if (input.available() > 0) {
                val c = input.read().toChar()
                if (c == '\r') { parse(riga.toString())?.let(onFrame); riga.clear() }
                else riga.append(c)
            } else Thread.sleep(2)
        }
    }

    override fun stop() {
        attivo = false
        comando("C")
    }

    private fun parse(riga: String): CanFrame? {
        if (riga.isEmpty() || riga[0] != 't') return null   // solo frame standard 11-bit
        val id = riga.substring(1, 4).toIntOrNull(16) ?: return null
        val len = riga.getOrNull(4)?.digitToIntOrNull() ?: return null
        val dati = (0 until len).mapNotNull {
            riga.substring(5 + it * 2, 7 + it * 2).toIntOrNull(16)
        }
        return CanFrame(System.currentTimeMillis(), id, dati.toIntArray())
    }

    private fun comando(c: String) {
        output.write("$c\r".toByteArray()); output.flush()
        Thread.sleep(30)
    }
}
