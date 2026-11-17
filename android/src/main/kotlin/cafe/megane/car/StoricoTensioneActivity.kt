package cafe.megane.car

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Schermata storico tensione 12V: mini-grafico + statistiche + verdetto.
 * Si aggiorna ogni 2 secondi mentre il service continua a registrare.
 */
class StoricoTensioneActivity : ComponentActivity() {

    private lateinit var grafico: TensioneGraficoView
    private lateinit var testo: TextView
    private lateinit var spark: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() { aggiorna(); handler.postDelayed(this, 2_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        grafico = TensioneGraficoView(this)
        testo = TextView(this).apply { setPadding(24, 24, 24, 8); textSize = 14f }
        spark = TextView(this).apply {
            setPadding(24, 0, 24, 24); textSize = 16f
            typeface = android.graphics.Typeface.MONOSPACE
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@StoricoTensioneActivity).apply {
                text = "Storico tensione 12V"; textSize = 20f; setPadding(24, 24, 24, 8)
            })
            addView(grafico, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 520
            ))
            addView(spark)
            addView(testo)
        })
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    private fun aggiorna() {
        val serie = TensioneLog.serie(this)
        grafico.mostra(serie)
        spark.text = if (serie.vuota) "" else serie.sparkline(28)
        testo.text = serie.riassunto()
    }
}
