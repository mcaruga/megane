package cafe.megane.car

import android.content.Context
import cafe.megane.core.SerieTensione
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro della tensione 12V nel tempo.
 *
 * Serve a dimostrare, dati alla mano, se l'intermittenza di Sport mode
 * e sound design dipende dall'alimentazione o no. Tu dici batteria carica:
 * questo log lo conferma o lo smentisce con i numeri, non a sensazione.
 *
 * Cio che conta non e il valore medio ma i CALI ISTANTANEI: una batteria
 * che misura 12.6 V a riposo puo crollare sotto 11 V all'avviamento o
 * quando inseriscono clima e fari. L'elettronica Renault va in protezione
 * proprio su quei transitori.
 */
object TensioneLog {

    private const val FILE = "tensione.csv"
    private const val SOGLIA_CALO = 12.2
    private var ultimaScrittura = 0L

    fun registra(ctx: Context, volt: Double?) {
        if (volt == null) return
        val ora = System.currentTimeMillis()
        val anomalia = volt < SOGLIA_CALO
        // scrive una riga al secondo, ma ogni anomalia la scrive sempre
        if (!anomalia && ora - ultimaScrittura < 1_000) return
        ultimaScrittura = ora

        file(ctx).appendText(
            "%s;%.2f;%s\n".format(
                Locale.ITALY,
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(Date(ora)),
                volt,
                if (anomalia) "CALO" else "",
            )
        )
    }

    fun file(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), FILE).also {
            if (!it.exists()) it.writeText("data_ora;volt;nota\n")
        }

    /** Serie storica per statistiche e grafico. Logica in core, testata. */
    fun serie(ctx: Context): SerieTensione =
        SerieTensione.daCsv(runCatching { file(ctx).readLines() }.getOrDefault(emptyList()))

    /** Riassunto da mostrare sul telefono: minimo, massimo, numero di cali. */
    fun riassunto(ctx: Context): String = serie(ctx).riassunto()
}
