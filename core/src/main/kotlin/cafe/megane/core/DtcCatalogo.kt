package cafe.megane.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class DtcFile(val dtc: Map<String, String>)

/**
 * Catalogo codici guasto in italiano, caricato da dtc-it.json.
 * Se un codice non c'e, ricade su una spiegazione per famiglia (la lettera
 * e il primo gruppo di cifre dicono comunque dove guardare).
 */
class DtcCatalogo(private val mappa: Map<String, String>) {

    fun spiega(codice: String): String {
        val c = codice.uppercase().trim()
        mappa[c]?.let { return it }
        return perFamiglia(c)
    }

    fun contiene(codice: String): Boolean = mappa.containsKey(codice.uppercase().trim())

    fun quanti(): Int = mappa.size

    private fun perFamiglia(c: String): String {
        if (c.length < 2) return "Codice non valido."
        val dominio = when (c[0]) {
            'P' -> "Powertrain (motore/cambio)"
            'C' -> "Chassis (ABS, ESP, sterzo)"
            'B' -> "Body (abitacolo, comfort)"
            'U' -> "Rete (comunicazione CAN tra centraline)"
            else -> "Sconosciuto"
        }
        val gruppo = when (c.getOrNull(2)) {
            '0', '1' -> when (c.getOrNull(1)) {
                '0' -> "standard SAE"
                else -> "specifico Renault"
            }
            else -> ""
        }
        return "Codice non ancora nel catalogo italiano. Dominio: $dominio" +
            (if (gruppo.isNotEmpty()) " ($gruppo)." else ".") +
            " Annotalo e cercalo per Megane IV TCe."
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun carica(testo: String): DtcCatalogo =
            DtcCatalogo(json.decodeFromString<DtcFile>(testo).dtc)
    }
}
