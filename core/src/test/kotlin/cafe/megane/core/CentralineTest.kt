package cafe.megane.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CentralineTest {

    @Test
    fun listaBiancaAccettaSoloLettura() {
        listOf("22F190", "22F187", "1902FF", "190101", "0100", "010C", "0902", "03", "07",
            "ATSH745", "ATCRA765", "ATFCSH745", "ATH0", "ATH1", "ATCM000", "ATST28", "ATZ".replace("ATZ", "ATD"))
            .forEach { assertTrue(ListaBianca.ammesso(it), it) }
    }

    @Test
    fun listaBiancaRifiutaScritturaSessioniECancellazione() {
        listOf(
            "2EF19001",     // scrive un dato
            "2F010203",     // controllo I/O
            "31010203",     // routine
            "1003", "1002", // sessione
            "2701", "2702", // sicurezza
            "1101",         // reset
            "14FFFFFF",     // cancella DTC (UDS)
            "04",           // cancella DTC (OBD)
            "3E00", "8501", "2801",
            "3401", "3601", "3701",
            "22F200",       // DID fuori da identificazione
            "220100",
            "1904FF",       // sotto-funzione non ammessa
            "ZZ", "", "AT", "ATZ", "ATWS", "ATPP0101SV01",
        ).forEach { assertFalse(ListaBianca.ammesso(it), it) }
    }

    @Test
    fun headerSoloDiagnostica() {
        assertFalse(ListaBianca.ammesso("ATSH7DF"))   // broadcast funzionale
        assertFalse(ListaBianca.ammesso("ATSH7E8"))   // e una risposta, non una richiesta
        assertFalse(ListaBianca.ammesso("ATSH123"))   // traffico normale del bus
        assertFalse(ListaBianca.ammesso("ATFCSH123"))
        assertTrue(ListaBianca.ammesso("ATSH7E0"))
        assertTrue(ListaBianca.ammesso("ATSH745"))
    }

    @Test
    fun elmRifiutaComandiNonAmmessiSenzaScrivere() {
        val out = java.io.ByteArrayOutputStream()
        val elm = Elm327(java.io.ByteArrayInputStream(ByteArray(0)), out)
        val r = runCatching { elm.comandoLettura("2EF19001") }
        assertTrue(r.isFailure)
        assertEquals(0, out.size())                   // niente e uscito verso l'adattatore
    }

    @Test
    fun parsaRispostaSemplice() {
        val e = Uds.interpreta("62F19056463152")
        assertTrue(e is Uds.Esito.Ok)
        assertEquals("VF1R", Uds.testo((e as Uds.Esito.Ok).dati.copyOfRange(3, 7)))
    }

    @Test
    fun parsaRispostaMultiframe() {
        val raw = "014\n0:62F19056463152\n1:4C37303030\n"
        val b = Uds.bytes(raw)
        assertEquals(0x62, b[0]); assertEquals(0xF1, b[1]); assertEquals(0x90, b[2])
        assertTrue(b.size >= 10)
    }

    @Test
    fun parsaRifiuto() {
        val e = Uds.interpreta("7F2231")
        assertTrue(e is Uds.Esito.Negativo)
        assertEquals(0x31, (e as Uds.Esito.Negativo).nrc)
        assertTrue(Uds.interpreta("NO DATA") is Uds.Esito.Niente)
    }

    @Test
    fun codiciGuastoDaRisposta() {
        // 59 02 FF | 01 23 4A 09 (P0123-4A attivo+confermato) | C0 01 00 08 (U0001 memorizzato)
        val b = Uds.bytes("59 02 FF 01 23 4A 09 C0 01 00 08".replace(" ", ""))
        val d = Uds.dtc(b)
        assertEquals(2, d.size)
        assertEquals("P0123", d[0].codice); assertEquals(0x4A, d[0].ftb); assertEquals("attivo", d[0].statoTesto())
        assertEquals("U0001", d[1].codice); assertTrue(d[1].statoTesto().startsWith("memorizzato"))
    }

    @Test
    fun scansioneSalvaERicarica() {
        val s = Scansione("2026-11-27 10:00", listOf(Centralina(0x745, 0x765, mapOf("F197" to "UCH"), listOf(DtcLetto("B1234", 1, 9)))))
        val c = ScansioneJson.da(ScansioneJson.a(s))
        assertEquals("UCH", c!!.centraline[0].ident["F197"])
        assertEquals("B1234", c.centraline[0].dtc!![0].codice)
    }
}
