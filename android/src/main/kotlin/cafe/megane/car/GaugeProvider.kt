package cafe.megane.car

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.abs

/**
 * Quadranti per Android Auto: ogni parametro e un'immagine PNG disegnata sul momento.
 * L'app multimediale puo mostrare solo righe/riquadri con una figura, quindi il
 * "quadrante" e la figura. Il valore e nell'indirizzo (?v=...): cambia il valore,
 * cambia l'indirizzo e Android Auto ricarica l'immagine.
 *
 *   content://cafe.megane.car.gauge/g/<pid>?v=<valore*10>   quadrante di un parametro
 *   content://cafe.megane.car.gauge/s?k=<0|1|2>             stato (0 spento, 1 quadro, 2 motore)
 *
 * Sola lettura: serve solo immagini, non espone altro.
 */
class GaugeProvider : ContentProvider() {

    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("sola lettura")
        val ctx = context ?: return null
        val dir = File(ctx.cacheDir, "gauge").apply { mkdirs() }
        val nome = (uri.path.orEmpty() + "_" + (uri.getQueryParameter("v") ?: uri.getQueryParameter("k") ?: ""))
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".png"
        val file = File(dir, nome)
        if (!file.exists()) {
            val bmp = disegna(ctx, uri) ?: return null
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            // i valori cambiano di continuo: tieni solo gli ultimi file
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun disegna(ctx: android.content.Context, uri: Uri): Bitmap? {
        val segmenti = uri.pathSegments
        return when (segmenti.firstOrNull()) {
            "g" -> {
                val pid = segmenti.getOrNull(1) ?: return null
                val def = StatoApp.stato(ctx)?.catalogo?.pids?.firstOrNull { it.pid == pid } ?: return null
                val v = uri.getQueryParameter("v")?.toDoubleOrNull()?.div(10.0)
                quadrante(v, def.min, def.max, def.unita, v?.let { def.fuoriRange(it) } ?: false)
            }
            "s" -> stato(uri.getQueryParameter("k")?.toIntOrNull() ?: 0)
            else -> null
        }
    }

    // -------- disegno --------

    private fun vernice(colore: Int, spessore: Float = 0f, stile: Paint.Style = Paint.Style.FILL) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colore; strokeWidth = spessore; style = stile; strokeCap = Paint.Cap.ROUND
        }

    private fun nuova(): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(DIM, DIM, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRoundRect(RectF(0f, 0f, DIM.toFloat(), DIM.toFloat()), 36f, 36f, vernice(SFONDO))
        return b to c
    }

    private fun testo(c: Canvas, t: String, cx: Float, y: Float, dim: Float, colore: Int, grassetto: Boolean) {
        val p = vernice(colore).apply {
            textSize = dim; textAlign = Paint.Align.CENTER
            typeface = if (grassetto) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        c.drawText(t, cx, y, p)
    }

    private fun quadrante(v: Double?, min: Double, max: Double, unita: String, allarme: Boolean): Bitmap {
        val (b, c) = nuova()
        val m = 44f
        val r = RectF(m, m, DIM - m, DIM - m)
        val sp = 30f
        // arco da 135 gradi, 270 di giro, come un contagiri
        c.drawArc(r, 135f, 270f, false, vernice(TRACCIA, sp, Paint.Style.STROKE))
        val colore = when {
            v == null -> TRACCIA
            allarme -> ROSSO
            else -> VERDE
        }
        if (v != null && max > min) {
            val frazione = ((v - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
            // sotto l'1% disegna comunque un punto, cosi si vede dove parte
            c.drawArc(r, 135f, maxOf(frazione * 270f, 2f), false, vernice(colore, sp, Paint.Style.STROKE))
        }
        val cx = DIM / 2f
        val valore = v?.let { if (abs(it) >= 100) "%.0f".format(it) else "%.1f".format(it) } ?: "--"
        testo(c, valore, cx, DIM / 2f + 28f, if (valore.length > 4) 70f else 88f, if (allarme) ROSSO else Color.WHITE, true)
        testo(c, unita, cx, DIM / 2f + 90f, 44f, GRIGIO, false)
        return b
    }

    private fun stato(k: Int): Bitmap {
        val (b, c) = nuova()
        val cx = DIM / 2f
        val colore = when (k) { 2 -> VERDE; 1 -> GIALLO; else -> GRIGIO }
        c.drawCircle(cx, DIM / 2f - 30f, 70f, vernice(colore))
        testo(c, when (k) { 2 -> "Motore"; 1 -> "Quadro"; else -> "Spento" }, cx, DIM / 2f + 100f, 56f, Color.WHITE, true)
        testo(c, when (k) { 2 -> "acceso"; 1 -> "acceso"; else -> "o non collegato" }, cx, DIM / 2f + 150f, 40f, GRIGIO, false)
        return b
    }

    // il resto del contratto ContentProvider: non serve niente
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        const val AUTORITA = "cafe.megane.car.gauge"
        private const val DIM = 360
        private val SFONDO = Color.parseColor("#1E2430")
        private val TRACCIA = Color.parseColor("#3A4252")
        private val VERDE = Color.parseColor("#4DD0A1")
        private val ROSSO = Color.parseColor("#FF5252")
        private val GIALLO = Color.parseColor("#FFC24D")
        private val GRIGIO = Color.parseColor("#9AA3B5")

        fun uriParametro(pid: String, valore: Double?): Uri =
            Uri.parse("content://$AUTORITA/g/$pid?v=${valore?.let { Math.round(it * 10) } ?: "x"}")

        fun uriStato(k: Int): Uri = Uri.parse("content://$AUTORITA/s?k=$k")
    }
}
