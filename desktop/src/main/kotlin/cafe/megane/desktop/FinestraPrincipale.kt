package cafe.megane.desktop

import cafe.megane.core.CanCorrelatore
import cafe.megane.core.Elm327
import cafe.megane.core.EngineGate
import cafe.megane.core.PidDecoder
import cafe.megane.core.SerieTensione
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.GridLayout
import javax.swing.*
import kotlin.concurrent.thread

/**
 * Finestra principale desktop. Sola lettura, stesso core della app Android.
 * Tre schede: Dati live, Tensione (grafico), Correlatore CAN.
 */
class FinestraPrincipale {

    private val catalogo = Risorse.catalogoPid()
    private val dtc = Risorse.catalogoDtc()
    private val correlatore = CanCorrelatore()
    private val tensioni = mutableListOf<Double>()

    @Volatile private var trasporto: Trasporto? = null
    @Volatile private var elm: Elm327? = null
    @Volatile private var inLettura = false

    private val areaDati = JTextArea().apply { isEditable = false; font = mono() }
    private val areaDtc = JTextArea().apply { isEditable = false; font = mono() }
    private val statoCorrelatore = JLabel("Correlatore fermo")
    private val grafico = PannelloGrafico()
    private val labelStato = JLabel("Non connesso")
    private val sceltaPorta = JComboBox<String>()

    fun mostra() {
        val frame = JFrame("Megane Diag - sola lettura")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.preferredSize = Dimension(720, 560)

        aggiornaPorte()
        frame.add(barraConnessione(), BorderLayout.NORTH)

        val schede = JTabbedPane().apply {
            addTab("Dati live", JScrollPane(areaDati))
            addTab("Tensione 12V", pannelloTensione())
            addTab("Codici (DTC)", JScrollPane(areaDtc))
            addTab("Correlatore CAN", pannelloCorrelatore())
        }
        frame.add(schede, BorderLayout.CENTER)
        frame.add(labelStato, BorderLayout.SOUTH)

        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    private fun barraConnessione() = JPanel().apply {
        add(JLabel("Porta:"))
        add(sceltaPorta)
        add(JButton("Aggiorna porte").apply { addActionListener { aggiornaPorte() } })
        add(JButton("Connetti").apply { addActionListener { connetti() } })
        add(JButton("Disconnetti").apply { addActionListener { disconnetti() } })
    }

    private fun pannelloTensione() = JPanel(BorderLayout()).apply {
        add(grafico, BorderLayout.CENTER)
    }

    private fun pannelloCorrelatore() = JPanel(BorderLayout()).apply {
        add(statoCorrelatore, BorderLayout.NORTH)
        add(JPanel(GridLayout(1, 4)).apply {
            add(JButton("Ascolto ON/OFF").apply { addActionListener { commutaMonitor() } })
            add(JButton("Funziona").apply {
                addActionListener { correlatore.marca(CanCorrelatore.Esito.OK); mostraCorrelatore() }
            })
            add(JButton("Non va").apply {
                addActionListener { correlatore.marca(CanCorrelatore.Esito.KO); mostraCorrelatore() }
            })
            add(JButton("Esporta CSV").apply { addActionListener { esportaCsv() } })
        }, BorderLayout.SOUTH)
    }

    private fun aggiornaPorte() {
        sceltaPorta.removeAllItems()
        Trasporto.porteProbabili().ifEmpty { Trasporto.tutteLePorte() }
            .forEach { sceltaPorta.addItem(it.systemPortName) }
        sceltaPorta.addItem("TCP 192.168.0.10:35000")
    }

    private fun connetti() {
        val scelta = sceltaPorta.selectedItem as? String ?: return
        thread {
            runCatching {
                trasporto = if (scelta.startsWith("TCP"))
                    Trasporto.Tcp("192.168.0.10", 35000)
                else Trasporto.Seriale(
                    Trasporto.tutteLePorte().first { it.systemPortName == scelta }
                )
                elm = trasporto!!.elm().also { it.connect().getOrThrow() }
            }.onSuccess { avviaLettura(); setStato("Connesso a $scelta") }
             .onFailure { setStato("Errore connessione: ${it.message}") }
        }
    }

    private fun disconnetti() {
        inLettura = false
        elm = null
        trasporto?.chiudi(); trasporto = null
        setStato("Disconnesso")
    }

    private fun avviaLettura() {
        inLettura = true
        thread {
            while (inLettura) {
                val e = elm ?: break
                if (!EngineGate.motoreAcceso(e)) { setStato("Motore spento"); Thread.sleep(2000); continue }
                val sb = StringBuilder()
                catalogo.pids.forEach { def ->
                    e.readPid(def.mode, def.pid)?.let { PidDecoder.decodifica(def, it) }?.let { v ->
                        if (def.pid == "42") { synchronized(tensioni) { tensioni.add(v) } }
                        sb.appendLine("%-34s %8.1f %s".format(def.traduzione, v, def.unita))
                    }
                }
                val codici = e.readDtc()
                SwingUtilities.invokeLater {
                    areaDati.text = sb.toString()
                    areaDtc.text = if (codici.isEmpty()) "Nessun codice guasto attivo"
                        else codici.joinToString("\n") { "$it  ${dtc.spiega(it)}" }
                    grafico.repaint()
                }
                Thread.sleep(500)
            }
        }
    }

    @Volatile private var inMonitor = false

    private fun commutaMonitor() {
        elm ?: run { setStato("Connetti prima il dongle"); return }
        if (inMonitor) { inMonitor = false; return }   // lo stop avviene al prossimo frame

        // in monitor i PID si fermano: non si puo fare entrambi sull'ELM327
        inLettura = false
        inMonitor = true
        statoCorrelatore.text = "Ascolto CAN attivo..."
        thread {
            runCatching {
                val mon = trasporto!!.monitor()
                mon.ascolta { frame ->
                    correlatore.osserva(frame)
                    if (!inMonitor) mon.stop()
                }
            }
            inMonitor = false
            SwingUtilities.invokeLater { mostraCorrelatore() }
            avviaLettura()   // tornato dai frame, riprende i PID
        }
    }

    private fun mostraCorrelatore() {
        val sospetti = correlatore.analizza()
        statoCorrelatore.text = "<html>${correlatore.statoRaccolta()}<br>" +
            (if (sospetti.isEmpty()) "Nessun ID sospetto"
             else sospetti.joinToString("<br>") {
                 "ID %03X  OK=%s  KO=%s".format(it.id, it.esempioOk, it.esempioKo)
             }) + "</html>"
    }

    private fun esportaCsv() {
        val chooser = JFileChooser()
        chooser.selectedFile = java.io.File("correlatore-megane.csv")
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.writeText(correlatore.esportaCsv())
            setStato("CSV salvato: ${chooser.selectedFile.absolutePath}")
        }
    }

    private fun setStato(s: String) = SwingUtilities.invokeLater { labelStato.text = s }
    private fun mono() = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13)

    /** Grafico tensione, stessa scala e soglie della app Android. */
    inner class PannelloGrafico : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val w = width; val h = height
            fun y(v: Double) = h - (((v - SerieTensione.SCALA_MIN) /
                (SerieTensione.SCALA_MAX - SerieTensione.SCALA_MIN)).coerceIn(0.0, 1.0) * h).toInt()

            g.color = Color(46, 125, 50, 40); g.fillRect(0, y(14.6), w, y(13.8) - y(14.6))
            g.color = Color(211, 47, 47); g.drawLine(0, y(12.2), w, y(12.2))
            g.drawString("12.2 V", 6, y(12.2) - 4)

            val dati = synchronized(tensioni) { tensioni.toList() }
            val serie = SerieTensione(dati)
            if (serie.vuota) { g.color = Color.GRAY; g.drawString("Nessun dato", 6, h / 2); return }
            val punti = serie.campiona(w.coerceAtMost(400))
            if (punti.size < 2) return
            g.color = Color(33, 150, 243)
            val passo = w.toDouble() / (punti.size - 1)
            for (i in 0 until punti.size - 1) {
                g.drawLine((i * passo).toInt(), y(punti[i]),
                    ((i + 1) * passo).toInt(), y(punti[i + 1]))
            }
        }
    }
}
