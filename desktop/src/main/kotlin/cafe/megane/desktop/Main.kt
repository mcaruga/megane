package cafe.megane.desktop

import cafe.megane.core.Catalogo
import cafe.megane.core.DtcCatalogo
import cafe.megane.core.PidDecoder
import cafe.megane.core.SerieTensione

/**
 * App desktop Megane Diag per macOS / Linux / Windows.
 *
 * Avvio:
 *   java -jar megane-desktop.jar              -> interfaccia grafica
 *   java -jar megane-desktop.jar --selftest   -> verifica senza schermo
 *   java -jar megane-desktop.jar --porte      -> elenca le porte seriali
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--selftest" -> selftest()
        "--porte" -> elencaPorte()
        else -> FinestraPrincipale().mostra()
    }
}

/** Carica i cataloghi dal classpath (sono nelle risorse del modulo core). */
object Risorse {
    fun catalogoPid(): Catalogo =
        PidDecoder.carica(leggi("/pids-it.json"))

    fun catalogoDtc(): DtcCatalogo =
        DtcCatalogo.carica(leggi("/dtc-it.json"))

    private fun leggi(path: String): String =
        Risorse::class.java.getResourceAsStream(path)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Risorsa mancante: $path")
}

/** Verifica a riga di comando: prova che il JAR e il core funzionano. */
private fun selftest() {
    println("=== MEGANE DIAG DESKTOP - SELFTEST ===")
    val pid = Risorse.catalogoPid()
    println("Catalogo PID caricato: ${pid.pids.size} parametri")
    println("  esempio: ${pid.pids.first { it.pid == "42" }.etichetta()}")

    val dtc = Risorse.catalogoDtc()
    println("Catalogo DTC caricato: ${dtc.quanti()} codici")
    println("  P0171 -> ${dtc.spiega("P0171")}")

    // decodifica di prova: tensione 0x37 0x70 = 14.192 V
    val def = pid.pids.first { it.pid == "42" }
    val v = PidDecoder.decodifica(def, intArrayOf(0x37, 0x70))
    println("Decodifica tensione di prova: $v V")

    val serie = SerieTensione(listOf(14.2, 11.8, 14.0, 11.5, 13.9))
    println("Serie tensione di prova:")
    println("  sparkline: ${serie.sparkline(16)}")
    println("  ${serie.verdetto()}")

    println("\nSELFTEST OK: core e risorse funzionano dentro il JAR.")
}

private fun elencaPorte() {
    println("Porte seriali rilevate:")
    Trasporto.tutteLePorte().forEach {
        println("  ${it.systemPortName}  (${it.descriptivePortName})")
    }
    val probabili = Trasporto.porteProbabili()
    println("\nProbabili dongle OBD: " +
        if (probabili.isEmpty()) "nessuno (accoppia il vLinker in Bluetooth)"
        else probabili.joinToString { it.systemPortName })
}
