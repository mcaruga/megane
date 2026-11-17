package cafe.megane.desktop

import cafe.megane.core.Elm327
import cafe.megane.core.ElmMonitor
import com.fazecast.jSerialComm.SerialPort
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Connessione al dongle OBD da desktop (macOS/Linux/Windows).
 *
 * Due vie:
 *  - SERIALE: il vLinker MC+ accoppiato in Bluetooth appare come porta
 *    seriale (/dev/tty.vLinker... su macOS, /dev/rfcomm0 su Linux, COMx su Windows).
 *  - TCP: dongle WiFi (di solito 192.168.0.10:35000). Sconsigliato: il WiFi
 *    del dongle occupa l'interfaccia di rete.
 *
 * In sola lettura come la app Android: stesso Elm327 del core, nessuna scrittura.
 */
sealed interface Trasporto {
    val input: InputStream
    val output: OutputStream
    fun chiudi()

    fun elm(): Elm327 = Elm327(input, output)
    fun monitor(): ElmMonitor = ElmMonitor(input, output)

    class Seriale(private val porta: SerialPort) : Trasporto {
        init {
            porta.setComPortParameters(38_400, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
            porta.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2_000, 0)
            require(porta.openPort()) { "Impossibile aprire ${porta.systemPortName}" }
        }
        override val input: InputStream get() = porta.inputStream
        override val output: OutputStream get() = porta.outputStream
        override fun chiudi() { runCatching { porta.closePort() } }
    }

    class Tcp(host: String, port: Int) : Trasporto {
        private val socket = Socket(host, port)
        override val input: InputStream get() = socket.getInputStream()
        override val output: OutputStream get() = socket.getOutputStream()
        override fun chiudi() { runCatching { socket.close() } }
    }

    companion object {
        /** Elenca le porte seriali che sembrano un dongle OBD. */
        fun porteProbabili(): List<SerialPort> =
            SerialPort.getCommPorts().filter { p ->
                val n = (p.systemPortName + " " + p.descriptivePortName).lowercase()
                listOf("vlinker", "obd", "elm", "rfcomm", "bluetooth", "usbserial", "tty.")
                    .any { n.contains(it) }
            }

        fun tutteLePorte(): List<SerialPort> = SerialPort.getCommPorts().toList()
    }
}
