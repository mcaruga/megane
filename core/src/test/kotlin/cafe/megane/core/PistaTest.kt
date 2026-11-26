package cafe.megane.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PistaTest {

    // circuito circolare r=100 m, 20 m/s, un fix al secondo: giro = 2*pi*100/20 = 31,4 s
    private val raggio = 100.0
    private val velocita = 20.0
    private val gradiPerMetro = 1.0 / 111_320.0

    private fun posizione(tSec: Double, v: Double = velocita): Pair<Double, Double> {
        val ang = v * tSec / raggio
        val x = raggio * sin(ang); val y = raggio * (1 - cos(ang))      // parte da (0,0) verso nord-est
        val lat = 45.0 + y * gradiPerMetro
        val lon = 9.0 + x * gradiPerMetro / cos(Math.toRadians(45.0))
        return lat to lon
    }

    private fun corri(s: SessionePista, da: Int, a: Int, v: Double = velocita) {
        for (t in da..a) { val (la, lo) = posizione(t.toDouble(), v); s.fix(la, lo, t * 1000L) }
    }

    @Test
    fun contaGiriEPrendeTempoGiusto() {
        val s = SessionePista()
        corri(s, 0, 3)
        assertTrue(s.impostaTraguardo())
        corri(s, 4, 140)                       // ~4 giri
        assertTrue(s.giri.size >= 3, "giri: ${s.giri}")
        s.giri.forEach { assertTrue(kotlin.math.abs(it.tempoMs - 31_416L) < 350L, "giro: ${it.tempoMs}") }
        assertNotNull(s.migliore)
    }

    @Test
    fun senzaTraguardoNonConta() {
        val s = SessionePista()
        corri(s, 0, 100)
        assertTrue(s.giri.isEmpty())
        assertFalse(s.inGiro)
        assertTrue(s.circuito().size > 50)
    }

    @Test
    fun deltaNegativoSeVaiPiuForte() {
        val s = SessionePista()
        corri(s, 0, 3)
        s.impostaTraguardo()
        corri(s, 4, 66)                        // primo giro a 20 m/s: ~31,4 s, miglior giro
        // poi 25 m/s: sullo stesso circuito il tempo parziale e minore del miglior giro
        for (t in 67..80) {
            val ang = (20.0 * 66 + 25.0 * (t - 66)) / raggio
            val lat = 45.0 + raggio * (1 - cos(ang)) * gradiPerMetro
            val lon = 9.0 + raggio * sin(ang) * gradiPerMetro / cos(Math.toRadians(45.0))
            s.fix(lat, lon, t * 1000L)
        }
        assertEquals(1, s.giri.size, "giri: ${s.giri}")
        val d = s.delta()
        assertNotNull(d)
        assertTrue(d < 0, "delta atteso negativo: $d")
        assertNull(SessionePista().delta())
    }

    @Test
    fun formattaTempo() {
        assertEquals("1:23.456", SessionePista.formatta(83_456))
        assertEquals("23.456", SessionePista.formatta(23_456))
        assertEquals("-0.250", SessionePista.formatta(-250))
    }
}
