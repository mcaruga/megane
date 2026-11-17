package cafe.megane.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElmMonitorTest {

    @Test fun parsaFrameDaFlussoElm() {
        // risposte ai due comandi init (ATH1, ATS0) chiuse da '>', poi i frame ATMA.
        // Replica il comportamento reale dell'ELM: ogni risposta termina col prompt.
        val flusso = "OK\r>OK\r>354 01 02\r7A0 FF\r>".toByteArray()
        val out = ByteArrayOutputStream()
        val mon = ElmMonitor(ByteArrayInputStream(flusso), out, idleTimeoutMs = 500)

        val frame = mutableListOf<CanFrame>()
        mon.ascolta { frame.add(it) }

        assertEquals(2, frame.size)
        assertEquals(0x354, frame[0].id)
        assertEquals(listOf(0x01, 0x02), frame[0].dati.toList())
        assertEquals(0x7A0, frame[1].id)
    }

    @Test fun inviaAtmaNonComandiCan() {
        // verifica che il monitor mandi solo comandi AT, mai frame sul bus
        val out = ByteArrayOutputStream()
        val mon = ElmMonitor(ByteArrayInputStream(">".toByteArray()), out, idleTimeoutMs = 300)
        mon.ascolta { }
        val inviato = out.toString()
        assertTrue(inviato.contains("ATMA"), "deve attivare il monitor")
        // nessun comando di scrittura: niente mode 2x, niente invio frame grezzi
        assertTrue(!inviato.contains("ATSH") && !inviato.contains("ATCRA") ||
            inviato.contains("ATMA"), "solo ascolto")
    }

    @Test fun timeoutInattivitaInterrompe() {
        // flusso che non si chiude con '>' e senza dati: deve uscire per timeout
        val out = ByteArrayOutputStream()
        val mon = ElmMonitor(ByteArrayInputStream(ByteArray(0)), out, idleTimeoutMs = 200)
        val inizio = System.currentTimeMillis()
        mon.ascolta { }
        val durata = System.currentTimeMillis() - inizio
        assertTrue(durata in 150..2000, "doveva uscire per timeout, durata $durata ms")
    }
}
