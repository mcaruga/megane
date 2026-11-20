package cafe.megane.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class EstraiPidTest {
    @Test fun rispostaSemplice() =
        assertContentEquals(intArrayOf(0x0B, 0xB8), estraiPid("410C0BB8", "01", "0C"))

    @Test fun conSpazi() =
        assertContentEquals(intArrayOf(0x0B, 0xB8), estraiPid("41 0C 0B B8", "01", "0C"))

    @Test fun conEcoDelComando() =
        assertContentEquals(intArrayOf(0x0B, 0xB8), estraiPid("010C\r410C0BB8", "01", "0C"))

    @Test fun piuCentraline() =
        assertContentEquals(intArrayOf(0x0B, 0xB8), estraiPid("410C0BB8\r410C0BB6", "01", "0C"))

    @Test fun rigaSpuria() =
        assertContentEquals(intArrayOf(0x5A), estraiPid("BUFFER FULL\r41055A", "01", "05"))

    @Test fun nessunDato() = assertNull(estraiPid("NO DATA", "01", "0C"))
    @Test fun nonConnesso() = assertNull(estraiPid("UNABLE TO CONNECT", "01", "0C"))
    @Test fun vuota() = assertNull(estraiPid("", "01", "0C"))
    @Test fun altroPid() = assertNull(estraiPid("41055A", "01", "0C"))
}
