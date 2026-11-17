package cafe.megane.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PidDef(
    val pid: String,
    val mode: String,
    val originale: String,
    val traduzione: String,
    val significato: String,
    val unita: String,
    val min: Double,
    val max: Double,
    val bytes: Int,
    val formula: String,
    val modificabile: String,
    val motore: List<String>,
    val eco: String? = null,
    val diagnosi: String? = null,
    @SerialName("allarme_sopra") val allarmeSopra: Double? = null,
    @SerialName("allarme_sotto") val allarmeSotto: Double? = null,
) {
    /** Etichetta come l'hai chiesta: originale + [traduzione] + [modificabile]. */
    fun etichetta(): String = "$originale [$traduzione] [${
        when (modificabile) {
            "no" -> "non modificabile, solo lettura"
            "officina" -> "modificabile solo in officina"
            else -> modificabile
        }
    }]"

    fun fuoriRange(v: Double): Boolean =
        (allarmeSopra?.let { v > it } ?: false) || (allarmeSotto?.let { v < it } ?: false)
}

@Serializable
data class ParametroNonAccessibile(
    val originale: String,
    val traduzione: String,
    val significato: String,
    val modificabile: String,
    val nota: String? = null,
)

@Serializable
data class Catalogo(
    val pids: List<PidDef>,
    @SerialName("parametri_non_accessibili") val nonAccessibili: List<ParametroNonAccessibile>,
)

object PidDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun carica(testo: String): Catalogo = json.decodeFromString(testo)

    /** Applica la formula del catalogo ai byte grezzi. */
    fun decodifica(def: PidDef, raw: IntArray): Double? {
        if (raw.size < def.bytes) return null
        val a = raw[0].toDouble()
        val b = if (raw.size > 1) raw[1].toDouble() else 0.0
        return when (def.formula) {
            "A" -> a
            "A-40" -> a - 40
            "A/2-64" -> a / 2 - 64
            "A*100/255" -> a * 100 / 255
            "(A-128)*100/128" -> (a - 128) * 100 / 128
            "(A*256+B)/4" -> (a * 256 + b) / 4
            "(A*256+B)/100" -> (a * 256 + b) / 100
            "(A*256+B)/1000" -> (a * 256 + b) / 1000
            "((A*256+B)/10)-40" -> ((a * 256 + b) / 10) - 40
            else -> null
        }
    }

    /** Boost turbo reale: MAP meno barometrica, non MAP meno 1013 fisso. */
    fun boostBar(mapKpa: Double, baroKpa: Double): Double = (mapKpa - baroKpa) / 100

    /** Consumo istantaneo da MAF. Benzina: AFR 14.7, densita 745 g/l. */
    fun litriPer100km(mafGs: Double, kmh: Double, diesel: Boolean = false): Double? {
        if (kmh < 5) return null
        val afr = if (diesel) 14.5 else 14.7
        val densita = if (diesel) 832.0 else 745.0
        return (mafGs * 3600) / (afr * densita * kmh) * 100
    }
}
