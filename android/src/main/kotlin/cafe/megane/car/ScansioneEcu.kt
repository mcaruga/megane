package cafe.megane.car

import android.content.Context
import cafe.megane.core.Centralina
import cafe.megane.core.Scansione
import cafe.megane.core.ScansioneJson

/**
 * Risultato dell'ultima lettura delle altre centraline (SOLA LETTURA).
 * Si fa dall'app sul telefono, a macchina ferma; il risultato resta salvato e si vede
 * anche sul display R-Link2 nella scheda "Centraline".
 */
object ScansioneEcu {
    @Volatile var progresso = ""
    @Volatile var inCorso = false
    @Volatile private var ultima: Scansione? = null

    fun risultato(ctx: Context): Scansione? {
        ultima?.let { return it }
        val t = ctx.getSharedPreferences("megane", Context.MODE_PRIVATE).getString("scansione_ecu", null)
        return t?.let { ScansioneJson.da(it) }?.also { ultima = it }
    }

    fun salva(ctx: Context, s: Scansione) {
        ultima = s
        ctx.getSharedPreferences("megane", Context.MODE_PRIVATE).edit()
            .putString("scansione_ecu", ScansioneJson.a(s)).apply()
    }

    /** Testo completo da leggere o condividere. */
    fun testo(s: Scansione): String = buildString {
        appendLine("Centraline lette il ${s.quando} (sola lettura): ${s.centraline.size}")
        s.centraline.forEach { c -> appendLine(); append(descrivi(c)) }
    }

    fun descrivi(c: Centralina): String = buildString {
        appendLine("%s  [richiesta %03X, risposta %03X]".format(c.nome(), c.richiesta, c.risposta))
        cafe.megane.core.LetturaCentraline.IDENT.forEach { (did, nome) ->
            c.ident["%04X".format(did)]?.let { appendLine("  $nome: $it") }
        }
        when {
            c.dtc == null -> appendLine("  Codici guasto: non letti ${c.nota}".trimEnd())
            c.dtc!!.isEmpty() -> appendLine("  Codici guasto: nessuno")
            else -> {
                appendLine("  Codici guasto: ${c.dtc!!.size}")
                c.dtc!!.forEach { appendLine("    ${it.codice}-%02X (${it.statoTesto()}): ${DtcItaliano.spiega(it.codice)}".format(it.ftb)) }
            }
        }
        if (c.nota.isNotBlank() && c.dtc != null) appendLine("  Nota: ${c.nota}")
    }
}
