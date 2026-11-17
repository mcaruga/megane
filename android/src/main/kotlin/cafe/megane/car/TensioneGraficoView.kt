package cafe.megane.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import cafe.megane.core.SerieTensione

/**
 * Mini-grafico della tensione 12V nel tempo.
 *
 * Disegna la curva, la banda sana (13.8-14.6 V) in verde tenue e la soglia
 * di calo (12.2 V) in rosso. Un occhio solo deve bastare: se la linea tocca
 * il rosso, c'e un transitorio che puo spiegare le funzioni a singhiozzo.
 */
class TensioneGraficoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var serie: SerieTensione = SerieTensione(emptyList())

    fun mostra(s: SerieTensione) { serie = s; invalidate() }

    private val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3"); strokeWidth = 4f
        style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val bandaSana = Paint().apply { color = Color.parseColor("#2E7D32"); alpha = 40 }
    private val lineaSoglia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F"); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val testo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; textSize = 28f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // mappa un valore tensione sulla Y del canvas (scala fissa 11-15 V)
        fun y(v: Double): Float {
            val norm = ((v - SerieTensione.SCALA_MIN) /
                (SerieTensione.SCALA_MAX - SerieTensione.SCALA_MIN)).coerceIn(0.0, 1.0)
            return h - (norm * h).toFloat()
        }

        // banda sana 13.8-14.6 V
        canvas.drawRect(0f, y(14.6), w, y(13.8), bandaSana)
        // soglia calo 12.2 V, tratteggiata idealmente
        canvas.drawLine(0f, y(12.2), w, y(12.2), lineaSoglia)
        canvas.drawText("12.2 V", 8f, y(12.2) - 6f, testo)

        if (serie.vuota) {
            canvas.drawText("Nessun dato registrato", 8f, h / 2, testo)
            return
        }

        val punti = serie.campiona(width.coerceAtMost(240))
        if (punti.size < 2) return
        val passo = w / (punti.size - 1)
        val path = Path().apply {
            moveTo(0f, y(punti[0]))
            punti.forEachIndexed { i, v -> lineTo(i * passo, y(v)) }
        }
        canvas.drawPath(path, linea)
    }
}
