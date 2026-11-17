package cafe.megane.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DtcTest {

    private fun catalogo(): DtcCatalogo {
        val testo = DtcTest::class.java.getResourceAsStream("/dtc-it.json")!!
            .bufferedReader().readText()
        return DtcCatalogo.carica(testo)
    }

    @Test fun catalogoAmpio() {
        assertTrue(catalogo().quanti() >= 40, "attesi 40+ codici DTC")
    }

    @Test fun codiceNotoSpiegato() {
        val s = catalogo().spiega("P0171")
        assertTrue(s.contains("magra"), "P0171 dovrebbe parlare di miscela magra")
    }

    @Test fun caseInsensitive() {
        assertEquals(catalogo().spiega("p0171"), catalogo().spiega("P0171"))
    }

    @Test fun codiceIgnotoRicadeSuFamiglia() {
        val s = catalogo().spiega("P0999")
        assertTrue(s.contains("Powertrain"), "fallback deve indicare il dominio")
    }

    @Test fun dominioRete() {
        val s = catalogo().spiega("U0999")
        assertTrue(s.contains("Rete") || s.contains("CAN"))
    }

    @Test fun catalanoU1000Presente() {
        assertTrue(catalogo().contiene("U1000"))
    }
}
