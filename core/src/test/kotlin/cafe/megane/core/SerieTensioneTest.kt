package cafe.megane.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerieTensioneTest {

    @Test fun serieVuota() {
        val s = SerieTensione(emptyList())
        assertTrue(s.vuota)
        assertTrue(s.sparkline().isEmpty())
        assertTrue(s.riassunto().contains("Nessun dato"))
    }

    @Test fun statisticheBase() {
        val s = SerieTensione(listOf(14.0, 12.0, 13.0))
        assertEquals(12.0, s.minimo)
        assertEquals(14.0, s.massimo)
        assertEquals(13.0, s.media, 1e-9)
    }

    @Test fun contaCali() {
        // soglia 12.2: 11.5 e 12.0 sono cali, 12.2 no (strettamente minore)
        val s = SerieTensione(listOf(14.1, 11.5, 12.0, 12.2, 13.9))
        assertEquals(2, s.cali())
    }

    @Test fun campionaRiduceAllaLarghezza() {
        val s = SerieTensione((1..1000).map { 13.0 })
        assertEquals(50, s.campiona(50).size)
    }

    @Test fun campionaNonIngrandisce() {
        val s = SerieTensione(listOf(13.0, 14.0))
        assertEquals(2, s.campiona(50).size)
    }

    @Test fun altezzeDentroIlRange() {
        val s = SerieTensione(listOf(11.0, 13.0, 15.0))
        val h = s.altezzeGrafico(3, 100)
        assertTrue(h.all { it in 0..100 })
        assertEquals(0, h.first())     // 11.0 = fondo scala
        assertEquals(100, h.last())    // 15.0 = cima scala
    }

    @Test fun altezzeClampSottoScala() {
        // 10 V sotto SCALA_MIN deve restare a 0, non negativo
        val s = SerieTensione(listOf(10.0))
        assertEquals(0, s.altezzeGrafico(1, 100).first())
    }

    @Test fun sparklineLunghezzaRichiesta() {
        val s = SerieTensione((1..200).map { 13.0 })
        assertEquals(24, s.sparkline(24).length)
    }

    @Test fun verdettoAlimentazioneSana() {
        val s = SerieTensione(listOf(14.2, 14.0, 14.4, 13.9))
        assertTrue(s.verdetto().contains("sana"))
    }

    @Test fun verdettoCaliSospetti() {
        val s = SerieTensione(listOf(14.2, 11.8, 14.0, 11.5))
        assertTrue(s.verdetto().contains("cali"))
    }

    @Test fun verdettoAlternatoreScarso() {
        // mai sopra 13.4: sospetto alternatore
        val s = SerieTensione(listOf(12.8, 13.0, 12.9))
        assertTrue(s.verdetto().contains("alternatore"))
    }

    @Test fun parsingCsvSaltaIntestazione() {
        val csv = listOf(
            "data_ora;volt;nota",
            "2026-01-01 10:00:00;14,20;",     // virgola decimale
            "2026-01-01 10:00:01;11.80;CALO", // punto decimale
            "riga;rotta",
        )
        val s = SerieTensione.daCsv(csv)
        assertEquals(2, s.letture)
        assertEquals(1, s.cali())
    }
}
