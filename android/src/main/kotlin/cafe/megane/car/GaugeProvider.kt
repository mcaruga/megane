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
import cafe.megane.core.SessionePista
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
        val file = file(ctx, uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** File PNG dell'immagine: lo disegna se non c'e gia. Usato anche per preparare le immagini in anticipo. */
    @Synchronized
    internal fun file(ctx: android.content.Context, uri: Uri): File? {
        val dir = File(ctx.cacheDir, "gauge").apply { mkdirs() }
        val nome = (uri.path.orEmpty() + "_" + uri.query.orEmpty())
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".png"
        val file = File(dir, nome)
        if (!file.exists()) {
            val bmp = disegna(ctx, uri) ?: return null
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            // i valori cambiano di continuo: tieni solo gli ultimi file
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(200)?.forEach { it.delete() }
        }
        return file
    }

    private fun disegna(ctx: android.content.Context, uri: Uri): Bitmap? {
        val segmenti = uri.pathSegments
        return when (segmenti.firstOrNull()) {
            "g" -> {
                val pid = segmenti.getOrNull(1) ?: return null
                val def = StatoApp.stato(ctx)?.catalogo?.pids?.firstOrNull { it.pid == pid } ?: return null
                val v = uri.getQueryParameter("v")?.toDoubleOrNull()?.div(100.0)
                val picco = uri.getQueryParameter("p")?.toDoubleOrNull()?.div(100.0)
                quadrante(v, def.min, def.max, def.unita, v?.let { def.fuoriRange(it) } ?: false,
                    v?.let { def.coloreFascia(it) }, picco)
            }
            "s" -> stato(uri.getQueryParameter("k")?.toIntOrNull() ?: 0)
            "c" -> circuito()
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

    private fun quadrante(v: Double?, min: Double, max: Double, unita: String, allarme: Boolean, fascia: String? = null, picco: Double? = null): Bitmap {
        val (b, c) = nuova()
        val m = 44f
        val r = RectF(m, m, DIM - m, DIM - m)
        val sp = 30f
        // arco da 135 gradi, 270 di giro, come un contagiri
        c.drawArc(r, 135f, 270f, false, vernice(TRACCIA, sp, Paint.Style.STROKE))
        // colore a fasce dal catalogo (verde, giallo, arancione, rosso, azzurro); senza fasce: verde o rosso
        val colore = when {
            v == null -> TRACCIA
            fascia != null -> colorePerNome(fascia)
            allarme -> ROSSO
            else -> VERDE
        }
        val rosso = (fascia == "rosso") || (fascia == null && allarme)
        if (v != null && max > min) {
            val frazione = ((v - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
            // sotto l'1% disegna comunque un punto, cosi si vede dove parte
            c.drawArc(r, 135f, maxOf(frazione * 270f, 2f), false, vernice(colore, sp, Paint.Style.STROKE))
        }
        // lancetta rossa del picco: dove e arrivato il valore da quando e acceso il quadro
        if (picco != null && max > min && picco > min) {
            val f = ((picco - min) / (max - min)).coerceIn(0.0, 1.0)
            val ang = Math.toRadians(135.0 + f * 270.0)
            val cx0 = DIM / 2f; val raggio = (DIM - 2 * m) / 2f
            val dentro = raggio - sp / 2f - 6f; val fuori = raggio + sp / 2f + 6f
            fun px(rr: Float) = cx0 + rr * Math.cos(ang).toFloat()
            fun py(rr: Float) = cx0 + rr * Math.sin(ang).toFloat()
            c.drawLine(px(dentro), py(dentro), px(fuori), py(fuori), vernice(ROSSO, 9f, Paint.Style.STROKE))
        }
        val cx = DIM / 2f
        val valore = v?.let { formatta(it, unita, soloNumero = true) } ?: "--"
        testo(c, valore, cx, DIM / 2f + 28f, if (valore.length > 4) 70f else 88f, if (rosso) ROSSO else Color.WHITE, true)
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

    /**
     * Modalita pista: disegno del circuito (traccia GPS del miglior giro, o del giro in corso)
     * con la macchina e la linea del traguardo, a destra i tempi.
     * Legge la sessione al momento del disegno; l'indirizzo cambia quando cambiano i dati.
     */
    private fun circuito(): Bitmap {
        val w = 960; val h = 480
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.parseColor("#0B0E14"))
        val s = PistaStato.sessione

        // ---- circuito (riquadro sinistro) ----
        val box = RectF(20f, 20f, 600f, 460f)
        c.drawRoundRect(box, 24f, 24f, vernice(SFONDO))
        val punti = s.circuito()
        val pos = s.posizione()
        val linea = s.linea()
        val tutti = punti + listOfNotNull(pos) + listOfNotNull(linea?.first, linea?.second)
        if (punti.size >= 2) {
            val minX = tutti.minOf { it.x }; val maxX = tutti.maxOf { it.x }
            val minY = tutti.minOf { it.y }; val maxY = tutti.maxOf { it.y }
            val dx = maxOf(maxX - minX, 20.0); val dy = maxOf(maxY - minY, 20.0)
            val m = 40f
            val scala = minOf((box.width() - 2 * m) / dx, (box.height() - 2 * m) / dy).toFloat()
            val ox = box.left + (box.width() - dx.toFloat() * scala) / 2f
            val oy = box.top + (box.height() - dy.toFloat() * scala) / 2f
            // nord in alto: la y dei metri cresce verso nord, quella dello schermo verso il basso
            fun sx(x: Double) = ox + ((x - minX).toFloat()) * scala
            fun sy(y: Double) = oy + ((maxY - y).toFloat()) * scala
            val path = android.graphics.Path()
            punti.forEachIndexed { i, p -> if (i == 0) path.moveTo(sx(p.x), sy(p.y)) else path.lineTo(sx(p.x), sy(p.y)) }
            c.drawPath(path, vernice(Color.parseColor("#8FA3C7"), 10f, Paint.Style.STROKE).apply {
                strokeJoin = Paint.Join.ROUND
            })
            linea?.let { (a, z) ->
                c.drawLine(sx(a.x), sy(a.y), sx(z.x), sy(z.y), vernice(Color.WHITE, 8f, Paint.Style.STROKE))
            }
            pos?.let { c.drawCircle(sx(it.x), sy(it.y), 14f, vernice(VERDE)) }
        } else {
            testo(c, "Nessuna traccia", box.centerX(), box.centerY() - 10f, 44f, Color.WHITE, true)
            testo(c, PistaStato.stato().take(48), box.centerX(), box.centerY() + 40f, 26f, GRIGIO, false)
        }

        // ---- tempi (colonna destra) ----
        val cx = 780f
        fun t(ms: Long?) = ms?.let { SessionePista.formatta(it) } ?: "--"
        testo(c, "GIRO", cx, 62f, 28f, GRIGIO, false)
        testo(c, t(PistaStato.tempoGiroMs()), cx, 128f, 64f, Color.WHITE, true)
        val d = if (PistaStato.gpsVivo()) s.delta() else null
        val coloreD = when { d == null -> GRIGIO; d <= 0 -> VERDE; else -> ROSSO }
        testo(c, "DISTACCO", cx, 190f, 28f, GRIGIO, false)
        testo(c, d?.let { (if (it > 0) "+" else "") + SessionePista.formatta(it) } ?: "--", cx, 245f, 52f, coloreD, true)
        testo(c, "ULTIMO", cx, 305f, 28f, GRIGIO, false)
        testo(c, t(s.ultimo?.tempoMs), cx, 350f, 44f, Color.WHITE, true)
        testo(c, "MIGLIORE", cx, 400f, 28f, GRIGIO, false)
        testo(c, t(s.migliore?.tempoMs), cx, 445f, 44f, GIALLO, true)
        testo(c, "giri ${s.giri.size}", 905f, 62f, 28f, GRIGIO, false)
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
        private val ARANCIONE = Color.parseColor("#FF8A3D")
        private val AZZURRO = Color.parseColor("#7FD6FF")

        private fun colorePerNome(n: String) = when (n) {
            "azzurro" -> AZZURRO
            "giallo" -> GIALLO
            "arancione" -> ARANCIONE
            "rosso" -> ROSSO
            else -> VERDE
        }
        private val GRIGIO = Color.parseColor("#9AA3B5")

        /** Cifre decimali per unita: giri e km/h interi, bar e g con due cifre. */
        private fun decimali(unita: String) = when (unita) {
            "rpm", "km/h", "C", "%", "kPa" -> 0
            "bar", "g" -> 2
            else -> 1
        }

        fun formatta(v: Double, unita: String, soloNumero: Boolean = false): String {
            val n = "%.${decimali(unita)}f".format(v)
            return if (soloNumero) n else "$n $unita"
        }

        /** Passo di arrotondamento per parametro: meno indirizzi diversi = meno ricaricamenti dell'immagine. */
        private fun passo(pid: String) = when (pid) {
            "0C" -> 10.0
            "0D", "04", "11", "05", "5C", "0F", "0B", "33" -> 1.0
            "BOOST", "DECEL" -> 0.05
            "10", "0E" -> 0.5
            else -> 0.1
        }

        private fun codifica(pid: String, v: Double?): String {
            if (v == null) return "x"
            val q = Math.round(v / passo(pid)) * passo(pid)
            return Math.round(q * 100).toString()
        }

        /** Indirizzo dell'immagine di un parametro. Il valore e arrotondato: cambia solo quando cambia la figura. */
        fun uriParametro(pid: String, valore: Double?, picco: Double? = null): Uri =
            Uri.parse("content://$AUTORITA/g/$pid?v=${codifica(pid, valore)}" +
                (picco?.let { "&p=${codifica(pid, it)}" } ?: ""))

        /** Disegna l'immagine in anticipo, cosi quando Android Auto la chiede e gia pronta. */
        fun prepara(ctx: android.content.Context, uri: Uri) { runCatching { GaugeProvider().file(ctx, uri) } }

        /** Circuito: l'indirizzo cambia quando cambiano i dati, cosi Android Auto ricarica l'immagine. */
        fun uriCircuito(): Uri {
            val s = PistaStato.sessione
            val q = listOfNotNull(
                "n=${s.circuito().size}", "g=${s.giri.size}", "l=${if (s.haTraguardo) 1 else 0}",
                PistaStato.tempoGiroMs()?.let { "t=${it / 100}" },
                s.delta()?.let { "d=${it / 100}" },
                s.posizione()?.let { "px=${Math.round(it.x)}&py=${Math.round(it.y)}" },
            )
            return Uri.parse("content://$AUTORITA/c?" + q.joinToString("&"))
        }

        fun uriStato(k: Int): Uri = Uri.parse("content://$AUTORITA/s?k=$k")
    }
}
