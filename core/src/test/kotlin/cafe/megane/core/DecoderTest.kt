package cafe.megane.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecoderTest {

    private fun def(formula: String, bytes: Int = 1) = PidDef(
        pid = "00", mode = "01", originale = "x", traduzione = "x", significato = "x",
        unita = "x", min = 0.0, max = 100.0, bytes = bytes, formula = formula,
        modificabile = "no", motore = listOf("TCe"),
    )

    @Test fun rpm() {
        // (A*256+B)/4 ; A=0x1A=26 B=0x00 -> 1664 rpm
        val d = def("(A*256+B)/4", 2)
        assertEquals(1664.0, PidDecoder.decodifica(d, intArrayOf(0x1A, 0x00)))
    }

    @Test fun temperaturaConOffset() {
        // A-40 ; A=0x5A=90 -> 50 gradi
        assertEquals(50.0, PidDecoder.decodifica(def("A-40"), intArrayOf(0x5A)))
    }

    @Test fun fuelTrimNegativo() {
        // (A-128)*100/128 ; A=128 -> 0 ; A=118 -> circa -7.8
        assertEquals(0.0, PidDecoder.decodifica(def("(A-128)*100/128"), intArrayOf(128)))
        val t = PidDecoder.decodifica(def("(A-128)*100/128"), intArrayOf(118))!!
        assertTrue(t < 0 && t > -8)
    }

    @Test fun tensione() {
        // (A*256+B)/1000 ; 0x37 0x70 = 14192 -> 14.192 V
        assertEquals(14.192, PidDecoder.decodifica(def("(A*256+B)/1000", 2), intArrayOf(0x37, 0x70)))
    }

    @Test fun byteInsufficienti() {
        assertNull(PidDecoder.decodifica(def("(A*256+B)/4", 2), intArrayOf(0x1A)))
    }

    @Test fun boostUsaBarometricaNonFisso() {
        // MAP 150, baro 98 -> 0.52 bar. NON (150-101.3)/100
        assertEquals(0.52, PidDecoder.boostBar(150.0, 98.0), 1e-9)
    }

    @Test fun consumoFermoNonCalcola() {
        assertNull(PidDecoder.litriPer100km(5.0, kmh = 0.0))
    }

    @Test fun consumoBenzinaPlausibile() {
        // 10 g/s a 100 km/h -> circa 3.3 l/100 in crociera leggera
        val l = PidDecoder.litriPer100km(10.0, 100.0)!!
        assertTrue(l in 2.0..5.0, "atteso 2-5 l/100, ottenuto $l")
    }

    @Test fun etichettaFormato() {
        val e = def("A").copy(originale = "Engine RPM", traduzione = "Giri").etichetta()
        assertEquals("Engine RPM [Giri] [non modificabile, solo lettura]", e)
    }

    @Test fun allarmeFuoriRange() {
        val d = def("A").copy(allarmeSopra = 110.0)
        assertTrue(d.fuoriRange(120.0))
        assertTrue(!d.fuoriRange(90.0))
    }

    @Test fun catalogoRealeSiCarica() {
        val testo = DecoderTest::class.java.getResourceAsStream("/pids-it.json")!!
            .bufferedReader().readText()
        val cat = PidDecoder.carica(testo)
        assertTrue(cat.pids.size >= 15, "attesi 15+ PID")
        assertTrue(cat.pids.any { it.pid == "42" }, "manca PID tensione")
        assertTrue(cat.nonAccessibili.isNotEmpty())
        // tutti sola lettura: nessuno deve essere scrivibile
        assertTrue(cat.pids.all { it.modificabile == "no" }, "un PID risulta scrivibile")
    }
}
