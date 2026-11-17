package cafe.megane.car

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import cafe.megane.core.Elm327
import cafe.megane.core.ElmMonitor
import java.util.UUID

/**
 * Collegamento Bluetooth classico (SPP) verso vLinker MC+.
 *
 * Scelte volute:
 *  - SPP e non BLE: su Android e piu stabile e non litiga con Android Auto.
 *  - Il socket si apre solo quando serve e si chiude subito dopo, cosi
 *    l'adattatore torna in sleep e la batteria resta a posto.
 */
class TrasportoBluetooth(private val adapter: BluetoothAdapter?) {

    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun dispositiviAccoppiati(): List<BluetoothDevice> =
        adapter?.bondedDevices?.filter { d ->
            val n = d.name?.lowercase() ?: ""
            NOMI_NOTI.any { n.contains(it) }
        } ?: emptyList()

    @SuppressLint("MissingPermission")
    fun apri(device: BluetoothDevice): Result<Elm327> = runCatching {
        chiudi()
        val s = device.createRfcommSocketToServiceRecord(SPP).also { socket = it }
        s.connect()
        Elm327(s.inputStream, s.outputStream).also { elm ->
            elm.connect().getOrThrow()
        }
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
        private val NOMI_NOTI = listOf("vlinker", "obdlink", "obdii", "icar", "elm327", "v-link")
    }
}
