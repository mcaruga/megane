package cafe.megane.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorrelatoreTest {

    private fun frame(id: Int, vararg b: Int) = CanFrame(0, id, b)

    @Test fun servonoEntrambiGliEsiti() {
        val c = CanCorrelatore()
        c.osserva(frame(0x354, 1)); c.marca(CanCorrelatore.Esito.OK)
        // solo OK: nessuna analisi possibile
        assertTrue(c.analizza().isEmpty())
    }

    @Test fun isolaIdCheCambiaCoerentemente() {
        val c = CanCorrelatore()
        // ID 0x354 vale 01 quando Sport va, 00 quando non va.
        // ID 0x100 e rumore: cambia a caso in entrambi.
        c.osserva(frame(0x354, 0x01)); c.osserva(frame(0x100, 0xAA))
        c.marca(CanCorrelatore.Esito.OK)
        c.osserva(frame(0x354, 0x01)); c.osserva(frame(0x100, 0xBB))
        c.marca(CanCorrelatore.Esito.OK)

        c.osserva(frame(0x354, 0x00)); c.osserva(frame(0x100, 0xAA))
        c.marca(CanCorrelatore.Esito.KO)
        c.osserva(frame(0x354, 0x00)); c.osserva(frame(0x100, 0xBB))
        c.marca(CanCorrelatore.Esito.KO)

        val sospetti = c.analizza()
        // 0x354 deve emergere, 0x100 no (compare sia in OK che in KO)
        assertTrue(sospetti.any { it.id == 0x354 }, "0x354 non isolato")
        assertTrue(sospetti.none { it.id == 0x100 }, "0x100 falso positivo")
    }

    @Test fun indicaIlByteGiusto() {
        val c = CanCorrelatore()
        c.osserva(frame(0x354, 0x12, 0x01, 0xFF)); c.marca(CanCorrelatore.Esito.OK)
        c.osserva(frame(0x354, 0x12, 0x01, 0xFF)); c.marca(CanCorrelatore.Esito.OK)
        c.osserva(frame(0x354, 0x12, 0x00, 0xFF)); c.marca(CanCorrelatore.Esito.KO)
        c.osserva(frame(0x354, 0x12, 0x00, 0xFF)); c.marca(CanCorrelatore.Esito.KO)

        val s = c.analizza().first { it.id == 0x354 }
        assertEquals(listOf(1), s.byteDiversi, "il byte diverso e l'indice 1")
    }

    @Test fun statoRaccoltaConta() {
        val c = CanCorrelatore()
        c.osserva(frame(0x1, 0)); c.marca(CanCorrelatore.Esito.OK)
        assertTrue(c.statoRaccolta().contains("OK: 1"))
    }

    @Test fun resetPulisce() {
        val c = CanCorrelatore()
        c.osserva(frame(0x1, 0)); c.marca(CanCorrelatore.Esito.OK)
        c.reset()
        assertTrue(c.statoRaccolta().contains("OK: 0"))
    }

    @Test fun frameEquivalenzaPerContenuto() {
        assertEquals(frame(0x354, 1, 2), frame(0x354, 1, 2))
        assertTrue(frame(0x354, 1, 2) != frame(0x354, 1, 3))
    }

    @Test fun csvContieneIdSospettoNonIlRumore() {
        val c = CanCorrelatore()
        c.osserva(frame(0x354, 0x01)); c.osserva(frame(0x100, 0xAA)); c.marca(CanCorrelatore.Esito.OK)
        c.osserva(frame(0x354, 0x01)); c.osserva(frame(0x100, 0xBB)); c.marca(CanCorrelatore.Esito.OK)
        c.osserva(frame(0x354, 0x00)); c.osserva(frame(0x100, 0xAA)); c.marca(CanCorrelatore.Esito.KO)
        c.osserva(frame(0x354, 0x00)); c.osserva(frame(0x100, 0xBB)); c.marca(CanCorrelatore.Esito.KO)

        val csv = c.esportaCsv()
        assertTrue(csv.lines().first().startsWith("id_can;"), "manca intestazione")
        assertTrue(csv.contains("354;"), "l'ID sospetto deve essere nel CSV")
        assertTrue(!csv.contains("100;"), "il rumore non deve finire nel CSV")
        assertTrue(csv.contains(";2;2"), "deve riportare i conteggi campioni")
    }

    @Test fun csvVuotoSenzaCampioni() {
        val righe = CanCorrelatore().esportaCsv().lines().filter { it.isNotBlank() }
        assertEquals(1, righe.size, "solo l'intestazione quando non c'e nulla")
    }
}
