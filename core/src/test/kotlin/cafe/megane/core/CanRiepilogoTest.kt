package cafe.megane.core

import kotlin.test.Test
import kotlin.test.assertTrue

class CanRiepilogoTest {
    @Test
    fun segnalaByteChePigliaValoriDiversi() {
        val r = CanRiepilogo()
        for (i in 0 until 20) {
            r.osserva(CanFrame(1000L + i * 100, 0x1A0, intArrayOf(0x10, 0x80 + i % 3, 0x00)))
        }
        r.osserva(CanFrame(3100L, 0x2B0, intArrayOf(0x01)))
        val t = r.testo()
        assertTrue(t.contains("1A0"), t)
        assertTrue(t.contains("[1(3)]"), t)       // il solo byte 1 varia, con 3 valori
        assertTrue(t.contains("2 ID") || t.contains("2 ID,"), t)
    }

    @Test
    fun busMutoDiceCosaSignifica() {
        assertTrue(CanRiepilogo().testo().contains("Nessun frame"))
    }
}
