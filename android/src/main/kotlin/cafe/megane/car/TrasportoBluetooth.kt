package cafe.megane.car

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import cafe.megane.core.Elm327
import cafe.megane.core.ElmMonitor
import java.io.IOException
import java.util.UUID

/**
 * Collegamento Bluetooth classico (SPP) verso vLinker MC+.
 *
 * Scelte volute:
 *  - SPP e non BLE: su Android e piu stabile e convive con la connessione
 *    vivavoce verso R-Link2 (profili diversi, il telefono li regge insieme).
 *  - Il socket si apre solo quando serve e si chiude subito dopo, cosi
 *    l'adattatore torna in sleep e la batteria resta a posto.
 */
class TrasportoBluetooth(private val adapter: BluetoothAdapter?) {

    private var socket: BluetoothSocket? = null

    /** Tutti i dispositivi accoppiati, quelli che sembrano adattatori OBD per primi. */
    @SuppressLint("MissingPermission")
    fun tuttiAccoppiati(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedByDescending { pareObd(it) } ?: emptyList()

    @SuppressLint("MissingPermission")
    fun pareObd(d: BluetoothDevice): Boolean {
        val n = d.name?.lowercase() ?: ""
        return NOMI_NOTI.any { n.contains(it) }
    }

    /** Quello scelto dall'utente; se non ha scelto, il primo con nome da adattatore OBD. */
    fun scegli(indirizzo: String?): BluetoothDevice? {
        val tutti = tuttiAccoppiati()
        return tutti.firstOrNull { it.address == indirizzo } ?: tutti.firstOrNull { pareObd(it) }
    }

    @SuppressLint("MissingPermission")
    fun apri(device: BluetoothDevice): Result<Elm327> = runCatching {
        chiudi()
        // una ricerca Bluetooth in corso fa fallire o rallenta la connessione
        runCatching { adapter?.cancelDiscovery() }
        val s = connettiSocket(device)
        Elm327(s.inputStream, s.outputStream).also { elm ->
            elm.connect().onFailure { chiudi() }.getOrThrow()
        }
    }

    /**
     * Tre tentativi, dal piu pulito al piu tollerante: molti cloni ELM327
     * rifiutano il socket "sicuro" ma accettano quello insecure o il canale 1.
     */
    @SuppressLint("MissingPermission")
    private fun connettiSocket(d: BluetoothDevice): BluetoothSocket {
        val modi = listOf<() -> BluetoothSocket>(
            { d.createRfcommSocketToServiceRecord(SPP) },
            { d.createInsecureRfcommSocketToServiceRecord(SPP) },
            {
                d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(d, 1) as BluetoothSocket
            },
        )
        var ultimo: Throwable? = null
        for (crea in modi) {
            val s = runCatching(crea).getOrElse { ultimo = it; null } ?: continue
            try {
                s.connect()
                socket = s
                return s
            } catch (e: IOException) {
                ultimo = e
                runCatching { s.close() }
            }
        }
        throw IOException(
            "il vLinker non accetta la connessione (${ultimo?.message ?: "?"}). " +
                "Quadro acceso? Un'altra app (es. quella del vLinker) e ancora collegata?",
            ultimo,
        )
    }

    /**
     * Monitor CAN passivo sulla STESSA connessione del dongle.
     * Mutuamente esclusivo con la lettura PID: finche il monitor ascolta,
     * l'ELM327 non risponde alle richieste PID. Lo gestisce PollingService.
     */
    fun monitorElm(): ElmMonitor? {
        val s = socket ?: return null
        return ElmMonitor(s.inputStream, s.outputStream)
    }

    fun chiudi() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val NOMI_NOTI = listOf("vlinker", "obdlink", "obd", "icar", "elm327", "v-link", "vgate")
    }
}
