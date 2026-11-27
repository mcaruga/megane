package cafe.megane.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StimaSerbatoioTest {
    private fun vicino(a: Double, b: Double, tol: Double = 0.05) = abs(a - b) <= tol

    @Test
    fun senzaLivelloNonSiSa() {
        val s = StimaSerbatoio()
        s.integra(10.0, 60.0)
        assertNull(s.litri)
    }

    @Test
    fun consumoSottraeLitri() {
        val s = StimaSerbatoio(capacita = 47.0)
        s.imposta(30.0)
        s.integra(6.0, 3600.0)            // 6 l/h per un'ora
        assertTrue(vicino(s.litri!!, 24.0), "${s.litri}")
    }

    @Test
    fun maf15gsPiu() {
        // 5 g/s di aria = 5/14,7/745 l/s = 1,644 l/h
        assertTrue(vicino(StimaSerbatoio.flussoDaMaf(5.0), 1.644, 0.01))
    }

    @Test
    fun pienoCalibra() {
        val s = StimaSerbatoio(capacita = 47.0)
        s.pieno(40.0)                       // primo pieno: nessuna calibrazione
        assertEquals(47.0, s.litri!!, 0.001)
        assertEquals(1.0, s.k, 1e-9)
        s.integra(10.0, 3600.0)             // calcolati 10 l ...
        s.pieno(12.0)                       // ... ma alla pompa 12: k sale
        assertTrue(s.k > 1.0 && s.k <= 1.4, "k=${s.k}")
        assertEquals(47.0, s.litri!!, 0.001)
    }

    @Test
    fun parzialiContanoNelPiuAvanti() {
        val s = StimaSerbatoio(capacita = 47.0)
        s.pieno(40.0)
        s.integra(10.0, 3600.0)             // 10 l calcolati
        s.aggiunti(5.0)                     // ... 5 aggiunti
        s.integra(10.0, 3600.0)             // altri 10 l -> 20 l calcolati in tutto
        s.pieno(25.0)                       // alla pompa 25 + 5 gia messi = 30 reali: k tende a 1,5 -> clampa 1,4
        assertTrue(s.k > 1.0, "k=${s.k}")
    }

    @Test
    fun impostaInvalidaLaCalibrazioneSuccessiva() {
        val s = StimaSerbatoio(capacita = 47.0)
        s.pieno(40.0)
        s.imposta(20.0)
        s.integra(10.0, 3600.0)
        s.pieno(30.0)                       // valido=false: k resta 1
        assertEquals(1.0, s.k, 1e-9)
    }

    @Test
    fun salvaECarica() {
        val s = StimaSerbatoio(capacita = 50.0)
        s.pieno(30.0)
        s.integra(5.0, 3600.0)
        val c = StimaSerbatoio.carica(s.salva())
        assertEquals(s.litri!!, c.litri!!, 1e-9)
        assertEquals(50.0, c.capacita, 1e-9)
    }
}
