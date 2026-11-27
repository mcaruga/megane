package cafe.megane.car

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var statoConn: TextView
    private lateinit var listaDongle: LinearLayout
    private lateinit var aggiorna: Button
    private lateinit var registra: Button
    private lateinit var serbTxt: TextView
    private lateinit var ecuTxt: TextView
    private lateinit var ecuBtn: Button
    private var registrando = false
    private val ui = Handler(Looper.getMainLooper())
    private val aggiornaStato = object : Runnable {
        override fun run() {
            statoConn.text = Connessione.testo
            if (::serbTxt.isInitialized) serbTxt.text = testoSerbatoio()
            if (::ecuBtn.isInitialized) aggiornaEcu()
            statoConn.setTextColor(if (Connessione.collegato) 0xFF2E7D32.toInt() else 0xFFB26A00.toInt())
            aggiorna.text = Aggiornamenti.stato.ifEmpty { "Controlla aggiornamenti" }
                .let { if (it.startsWith("Disponibile")) "AGGIORNA: $it" else it }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statoConn = TextView(this).apply { setPadding(32, 32, 32, 8); textSize = 17f }
        listaDongle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        Connessione.ctx = applicationContext
        Diagnosi.evento(this, "APP: aperta sul telefono")
        val diagnosi = Button(this).apply {
            text = "Condividi diagnosi (mandala a chi ti aiuta)"
            setOnClickListener { Diagnosi.condividi(this@TelefonoActivity) }
        }
        aggiorna = Button(this).apply {
            isAllCaps = false
            text = "Controlla aggiornamenti"
            setOnClickListener { aggiornaOra() }
        }
        val riprova = Button(this).apply {
            text = "Riprova connessione adesso"
            setOnClickListener { PollingService.avvia(this@TelefonoActivity, riprova = true) }
        }
        val ferma = Button(this).apply {
            text = "Ferma Megane (libera il vLinker)"
            setOnClickListener { PollingService.ferma(this@TelefonoActivity) }
        }
        val posizione = Button(this).apply {
            isAllCaps = false
            text = "Modalita pista: dai il permesso posizione (GPS)"
            setOnClickListener { chiediPosizione() }
        }
        registra = Button(this).apply {
            isAllCaps = false
            text = "Registra bus CAN 60 s (cerca pressione gomme, benzina)"
            setOnClickListener { registraCan() }
        }
        // ---- altre centraline: SOLA LETTURA (identificazione e codici guasto) ----
        ecuBtn = Button(this).apply {
            isAllCaps = false
            text = "Leggi le altre centraline (sola lettura, circa 1 minuto)"
            setOnClickListener { avviaLetturaEcu() }
        }
        ecuTxt = TextView(this).apply {
            setPadding(32, 16, 32, 16); textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val ecuCondividi = Button(this).apply {
            isAllCaps = false
            text = "Condividi la lettura delle centraline"
            setOnClickListener {
                val r = ScansioneEcu.risultato(this@TelefonoActivity)
                if (r == null) android.widget.Toast.makeText(context, "Nessuna lettura ancora", android.widget.Toast.LENGTH_SHORT).show()
                else startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Centraline Megane")
                        putExtra(android.content.Intent.EXTRA_TEXT, ScansioneEcu.testo(r))
                    }, "Condividi centraline"))
            }
        }
        // ---- benzina stimata: tu dici i litri, l'app sottrae il consumo e si calibra ai pieni ----
        serbTxt = TextView(this).apply { setPadding(32, 16, 32, 0); textSize = 15f }
        val serbIn = android.widget.EditText(this).apply {
            hint = "litri (es. 38,5)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        fun litriInseriti(): Double? = serbIn.text.toString().replace(',', '.').toDoubleOrNull()
            ?.takeIf { it > 0 }
            .also { if (it == null) android.widget.Toast.makeText(this, "Scrivi i litri", android.widget.Toast.LENGTH_SHORT).show() }
        fun serb(azione: (StatoVeicolo, Double) -> Unit) {
            val st = StatoApp.stato(this) ?: return
            val l = litriInseriti() ?: return
            azione(st, l); st.salvaSerbatoio(); serbIn.text.clear()
            serbTxt.text = testoSerbatoio()
        }
        val serbBottoni = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(context).apply {
                isAllCaps = false; text = "Pieno fatto: ho messo questi litri"
                setOnClickListener { serb { st, l -> st.serbatoio.pieno(l) } }
            })
            addView(Button(context).apply {
                isAllCaps = false; text = "Ho aggiunto questi litri (non pieno)"
                setOnClickListener { serb { st, l -> st.serbatoio.aggiunti(l) } }
            })
            addView(Button(context).apply {
                isAllCaps = false; text = "Ora nel serbatoio ci sono questi litri"
                setOnClickListener { serb { st, l -> st.serbatoio.imposta(l) } }
            })
            addView(Button(context).apply {
                isAllCaps = false; text = "Capacita del serbatoio = questi litri"
                setOnClickListener { serb { st, l -> st.serbatoio.capacita = l.coerceIn(20.0, 100.0) } }
            })
        }
        testo = TextView(this).apply {
            setPadding(32, 32, 32, 32); textSize = 14f
            // tocco sul blocco: apre lo storico tensione con grafico
            setOnClickListener {
                startActivity(android.content.Intent(
                    this@TelefonoActivity, StoricoTensioneActivity::class.java))
            }
        }
        val colonna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statoConn)
            addView(aggiorna)
            addView(diagnosi)
            addView(TextView(context).apply {
                setPadding(32, 16, 32, 0); textSize = 14f
                text = "ADATTATORE (tocca quello giusto; deve essere gia accoppiato nel Bluetooth del telefono, PIN 1234):"
            })
            addView(listaDongle)
            addView(riprova)
            addView(ferma)
            addView(posizione)
            addView(registra)
            addView(ecuBtn)
            addView(ecuTxt)
            addView(ecuCondividi)
            addView(serbTxt)
            addView(serbIn)
            addView(serbBottoni)
            addView(testo)
        }
        setContentView(ScrollView(this).apply { addView(colonna) })

        if (chiediPermessi()) avviaServizio()
        mostra()
        if (intent?.getBooleanExtra(Aggiornamenti.EXTRA_AGGIORNA, false) == true) aggiornaOra()
        else lifecycleScope.launch(Dispatchers.IO) { runCatching { Aggiornamenti.controlla(applicationContext) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Aggiornamenti.EXTRA_AGGIORNA, false)) aggiornaOra()
    }

    /** Tocco su notifica o pulsante: controlla, scarica, verifica, installa. */
    private fun aggiornaOra() {
        aggiorna.isEnabled = false
        lifecycleScope.launch {
            val nuova = withContext(Dispatchers.IO) { runCatching { Aggiornamenti.controlla(applicationContext) }.getOrNull() }
            if (nuova == null) { aggiorna.isEnabled = true; return@launch }
            if (!Aggiornamenti.puoInstallare(this@TelefonoActivity)) {
                aggiorna.isEnabled = true
                android.widget.Toast.makeText(this@TelefonoActivity,
                    "Consenti a Megane di installare app, poi torna qui e tocca di nuovo Aggiorna",
                    android.widget.Toast.LENGTH_LONG).show()
                Aggiornamenti.apriPermessoInstallazione(this@TelefonoActivity)
                return@launch
            }
            val r = withContext(Dispatchers.IO) { Aggiornamenti.scaricaEInstalla(applicationContext) }
            aggiorna.isEnabled = true
            r.onFailure {
                android.widget.Toast.makeText(this@TelefonoActivity,
                    "Aggiornamento: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() { super.onResume(); mostra(); ui.post(aggiornaStato) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(aggiornaStato) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        avviaServizio()
        mostra()
    }

    @Suppress("MissingPermission")
    private fun mostraDongle(trasporto: TrasportoBluetooth) {
        listaDongle.removeAllViews()
        val tutti = runCatching { trasporto.tuttiAccoppiati() }.getOrNull()
        if (tutti == null) {
            listaDongle.addView(TextView(this).apply { text = "  (serve il permesso Bluetooth)" })
            return
        }
        if (tutti.isEmpty()) {
            listaDongle.addView(TextView(this).apply {
                text = "  Nessun dispositivo accoppiato. Impostazioni > Bluetooth > Accoppia nuovo: vLinker (PIN 1234)."
            })
            return
        }
        val scelto = trasporto.scegli(Connessione.indirizzoScelto(this))?.address
        tutti.forEach { d ->
            listaDongle.addView(Button(this).apply {
                isAllCaps = false
                val segno = if (d.address == scelto) "✓ " else ""
                val obd = if (trasporto.pareObd(d)) "" else "  (non sembra OBD)"
                text = "$segno${nomeDongle(d)}  ${d.address}$obd"
                setOnClickListener {
                    Connessione.scegli(this@TelefonoActivity, d.address)
                    Connessione.imposta("Scelto ${nomeDongle(d)}: mi collego...")
                    PollingService.avvia(this@TelefonoActivity, riprova = true)
                    mostra()
                }
            })
        }
    }

    private fun mostra() {
        val stato = StatoApp.stato(this)
        val bt = getSystemService(BluetoothManager::class.java)
        val trasporto = TrasportoBluetooth(bt?.adapter)
        mostraDongle(trasporto)

        testo.text = buildString {
            appendLine()
            if (Connessione.versione.isNotEmpty()) appendLine("Adattatore: ${Connessione.versione}").appendLine()

            appendLine("STATO AUTO:")
            if (stato == null) appendLine("  catalogo non caricato")
            else {
                appendLine("  motore: ${when { stato.motoreAcceso -> "acceso"; stato.quadroAcceso -> "fermo, quadro acceso"; else -> "spento o centralina muta" }}")
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

    /** Solo a macchina ferma e quadro acceso: la lettura occupa il bus diagnostico per circa un minuto. */
    private fun avviaLetturaEcu() {
        val st = StatoApp.stato(this) ?: return
        val v = st.valore("0D")
        when {
            ScansioneEcu.inCorso -> return
            !st.quadroAcceso -> android.widget.Toast.makeText(this,
                "Quadro acceso e adattatore collegato, poi riprova", android.widget.Toast.LENGTH_LONG).show()
            v != null && v >= 1.0 -> android.widget.Toast.makeText(this,
                "Fermati: la lettura si fa solo a macchina ferma", android.widget.Toast.LENGTH_LONG).show()
            else -> {
                ScansioneEcu.progresso = "In attesa dell'adattatore..."
                st.modalita = StatoVeicolo.Modalita.LETTURA_ECU
            }
        }
    }

    private fun aggiornaEcu() {
        val r = ScansioneEcu.risultato(this)
        ecuBtn.isEnabled = !ScansioneEcu.inCorso
        ecuBtn.text = if (ScansioneEcu.inCorso) ScansioneEcu.progresso
        else "Leggi le altre centraline (sola lettura, circa 1 minuto)"
        ecuTxt.text = when {
            ScansioneEcu.inCorso -> ""
            r == null -> "Nessuna lettura. A macchina ferma e quadro acceso tocca il pulsante qui sopra."
            else -> ScansioneEcu.testo(r)
        }
    }

    private fun testoSerbatoio(): String {
        val st = StatoApp.stato(this) ?: return "BENZINA: non disponibile"
        val s = st.serbatoio
        val l = s.litri
        return if (l == null) "BENZINA (stima): dimmi quanti litri ci sono ora (guarda l'indicatore dell'auto) e scrivili qui sotto."
        else "BENZINA (stima): %.1f di %.0f L (%.0f%%). Fattore di calibrazione %.2f. Dopo 2-3 pieni e piu precisa.".format(
            l, s.capacita, (s.frazione() ?: 0.0) * 100, s.k)
    }

    /**
     * Ascolto passivo del bus CAN per 60 s, poi riassunto da condividere: serve a trovare
     * i messaggi con pressione gomme o benzina. Non invia nulla all'auto.
     * Fai l'ascolto con il quadro acceso, e confronta con quello che dice il quadro strumenti.
     */
    private fun registraCan() {
        if (registrando) return
        val st = StatoApp.stato(this) ?: return
        registrando = true
        st.riepilogo.azzera()
        st.modalita = StatoVeicolo.Modalita.CAN_MONITOR
        val fine = System.currentTimeMillis() + 60_000
        val passo = object : Runnable {
            override fun run() {
                val resto = (fine - System.currentTimeMillis()) / 1000
                if (resto > 0) {
                    registra.text = "Registro il bus CAN... ${resto} s  (${st.riepilogo.totale()} frame, ${st.riepilogo.quantiId()} ID)"
                    ui.postDelayed(this, 1000)
                } else {
                    st.modalita = StatoVeicolo.Modalita.PID
                    registrando = false
                    registra.text = "Registra bus CAN 60 s (cerca pressione gomme, benzina)"
                    // riparte pulita: dopo ATMA l'adattatore e in modalita monitor
                    PollingService.avvia(this@TelefonoActivity, riprova = true)
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Registrazione CAN Megane")
                        putExtra(android.content.Intent.EXTRA_TEXT, st.riepilogo.testo())
                    }
                    startActivity(android.content.Intent.createChooser(i, "Condividi registrazione CAN"))
                }
            }
        }
        ui.post(passo)
    }

    /**
     * Posizione per la modalita pista, in due passi: prima "mentre usi l'app", poi "sempre"
     * (serve perche con Android Auto l'app del telefono e in secondo piano).
     */
    private fun chiediPosizione() {
        fun ok(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        when {
            !ok(Manifest.permission.ACCESS_FINE_LOCATION) ->
                ActivityCompat.requestPermissions(this, arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
            !ok(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> {
                android.widget.Toast.makeText(this,
                    "Scegli \"Consenti sempre\" per usare il GPS con Android Auto",
                    android.widget.Toast.LENGTH_LONG).show()
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3)
            }
            else -> android.widget.Toast.makeText(this, "Permesso posizione gia dato", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** true se i permessi ci sono gia. */
    private fun chiediPermessi(): Boolean {
        val servono = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) servono += Manifest.permission.BLUETOOTH_CONNECT
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) servono += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) servono += Manifest.permission.POST_NOTIFICATIONS
        if (servono.isNotEmpty())
            ActivityCompat.requestPermissions(this, servono.toTypedArray(), 1)
        return servono.none { it != Manifest.permission.POST_NOTIFICATIONS }
    }
}
