package cafe.megane.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FasceTest {
    private val catalogo = PidDecoder.carica(
        javaClass.classLoader.getResourceAsStream("pids-it.json")!!.bufferedReader().readText()
    )

    private fun def(pid: String) = catalogo.pids.first { it.pid == pid }

    @Test
    fun velocitaVerdeGialloRosso() {
        val d = def("0D")
        assertEquals("verde", d.coloreFascia(90.0))
        assertEquals("giallo", d.coloreFascia(150.0))
        assertEquals("rosso", d.coloreFascia(200.0))
    }

    @Test
    fun caricoArancioneEPoiRosso() {
        val d = def("04")
        assertEquals("verde", d.coloreFascia(50.0))
        assertEquals("giallo", d.coloreFascia(75.0))
        assertEquals("arancione", d.coloreFascia(85.0))
        assertEquals("rosso", d.coloreFascia(95.0))
    }

    @Test
    fun temperaturaAcquaFreddaAzzurraGiustaVerdeTroppaRossa() {
        val d = def("05")
        assertEquals("azzurro", d.coloreFascia(46.0))
        assertEquals("verde", d.coloreFascia(90.0))
        assertEquals("rosso", d.coloreFascia(118.0))
    }

    @Test
    fun senzaFasceNiente() {
        assertNotNull(def("10"))
        assertEquals(null, def("10").coloreFascia(5.0))
    }
}
