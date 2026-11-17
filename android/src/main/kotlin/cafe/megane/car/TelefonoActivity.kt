package cafe.megane.car

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Schermata sul telefono. Qui scegli il dongle, vedi il log tensione,
 * avvii il correlatore e sfogli il catalogo completo dei parametri.
 *
 * Volutamente spartana: il posto bello e il cruscotto su R-Link2.
 * Questa e la plancia tecnica.
 */
class TelefonoActivity : ComponentActivity() {

    private lateinit var testo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        testo = TextView(this).apply {
            setPadding(32, 32, 32, 32); textSize = 14f
            // tocco sul blocco: apre lo storico tensione con grafico
            setOnClickListener {
                startActivity(android.content.Intent(
                    this@TelefonoActivity, StoricoTensioneActivity::class.java))
            }
        }
        setContentView(ScrollView(this).apply { addView(testo) })

        chiediPermessi()
        avviaServizio()
        mostra()
    }

    override fun onResume() { super.onResume(); mostra() }

    private fun mostra() {
        val stato = StatoApp.stato(this)
        val bt = getSystemService(BluetoothManager::class.java)
        val trasporto = TrasportoBluetooth(bt?.adapter)
        val dongle = runCatching { trasporto.dispositiviAccoppiati() }.getOrNull().orEmpty()

        testo.text = buildString {
            appendLine("=== MEGANE DIAG ===").appendLine()

            appendLine("DONGLE OBD ACCOPPIATI:")
            if (dongle.isEmpty())
                appendLine("  nessuno. Accoppia il vLinker MC+ nelle impostazioni Bluetooth.")
            else dongle.forEach { appendLine("  - ${nomeDongle(it)}") }
            appendLine()

            appendLine("STATO AUTO:")
            if (stato == null) appendLine("  catalogo non caricato")
            else {
                appendLine("  motore: ${if (stato.motoreAcceso) "acceso" else "spento"}")
                stato.parametriScelti.forEach { def ->
                    val v = stato.valore(def.pid)?.let { "%.1f %s".format(it, def.unita) } ?: "--"
                    appendLine("  ${def.traduzione}: $v")
                }
                if (stato.dtc.isNotEmpty()) {
                    appendLine().appendLine("  CODICI GUASTO:")
                    stato.dtc.forEach { appendLine("   $it - ${DtcItaliano.spiega(it)}") }
                }
            }
            appendLine()

            appendLine("LOG TENSIONE 12V (tocca per il grafico):")
            val serie = TensioneLog.serie(this@TelefonoActivity)
            if (!serie.vuota) appendLine("  ${serie.sparkline(28)}")
            append(serie.riassunto().prependIndent("  "))
            appendLine().appendLine()

            appendLine("CATALOGO PARAMETRI (originale [traduzione] [modificabile]):")
            stato?.catalogo?.pids?.forEach { appendLine("  ${it.etichetta()}") }
            appendLine()
            appendLine("NON ACCESSIBILI DA APP:")
            stato?.catalogo?.nonAccessibili?.forEach {
                appendLine("  ${it.originale} [${it.traduzione}] -> ${it.modificabile}")
            }
        }
    }

    @Suppress("MissingPermission")
    private fun nomeDongle(d: android.bluetooth.BluetoothDevice): String =
        runCatching { d.name ?: d.address }.getOrDefault("sconosciuto")

    private fun avviaServizio() {
        runCatching { PollingService.avvia(this) }
    }

    private fun chiediPermessi() {
        val servono = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) servono += Manifest.permission.BLUETOOTH_CONNECT
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) servono += Manifest.permission.BLUETOOTH_SCAN
        }
        if (servono.isNotEmpty())
            ActivityCompat.requestPermissions(this, servono.toTypedArray(), 1)
    }
}
