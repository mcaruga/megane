package cafe.megane.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnosi da condividere: cosa vede il telefono di Android Auto e del vLinker.
 * Il registro eventi e salvato su disco: Android Auto puo avviare il servizio
 * anche ad app chiusa, e vogliamo sapere se l'ha fatto.
 */
object Diagnosi {
    private const val PREFS = "diagnosi"
    private const val CHIAVE = "eventi"
    private const val MAX = 60
    private const val GEARHEAD = "com.google.android.projection.gearhead"
    private val ora = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY)

    @Synchronized
    fun evento(ctx: Context, testo: String) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val righe = (p.getString(CHIAVE, "") ?: "").lines().filter { it.isNotBlank() }
            .takeLast(MAX - 1) + "${ora.format(Date())} $testo"
        p.edit().putString(CHIAVE, righe.joinToString("\n")).apply()
    }

    private fun eventi(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHIAVE, "")
            ?.ifBlank { "(nessuno)" } ?: "(nessuno)"

    @Suppress("DEPRECATION")
    fun rapporto(ctx: Context): String = buildString {
        val pm = ctx.packageManager
        val mio = runCatching { pm.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        appendLine("=== DIAGNOSI MEGANE ===")
        appendLine("App: ${mio?.versionName} (${mio?.longVersionCode})")
        appendLine("Telefono: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        val um = ctx.getSystemService(UserManager::class.java)
        val utente = Process.myUserHandle().hashCode()
        appendLine("Profilo utente: $utente${if (utente != 0) "  <-- NON e il profilo principale!" else " (principale)"}" +
            (if (runCatching { um.isManagedProfile }.getOrDefault(false)) " [profilo di lavoro]" else ""))

        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                pm.getInstallSourceInfo(ctx.packageName).installingPackageName
            else pm.getInstallerPackageName(ctx.packageName)
        }.getOrNull()
        appendLine("Installata da: ${installer ?: "sconosciuto (APK)"}")

        val aa = runCatching { pm.getPackageInfo(GEARHEAD, 0) }.getOrNull()
        appendLine("Android Auto: ${aa?.versionName ?: "NON TROVATO"}" +
            (aa?.let { " abilitato=${it.applicationInfo?.enabled}" } ?: ""))

        // le app per auto che il sistema conosce: se la nostra manca, Android Auto non puo vederla
        val auto = runCatching {
            pm.queryIntentServices(Intent("androidx.car.app.CarAppService"), PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        appendLine("App per auto (Car App Library) viste dal sistema: ${auto.size}")
        auto.forEach { r ->
            val s = r.serviceInfo
            val cat = r.filter?.categoriesIterator()?.asSequence()?.joinToString(",")
                ?.replace("androidx.car.app.category.", "") ?: "?"
            appendLine("  - ${s.packageName} [$cat] exported=${s.exported} enabled=${s.enabled}")
        }
        val legacy = runCatching {
            pm.queryIntentServices(Intent("com.google.android.gms.car.category.CATEGORY_PROJECTION"), 0).size
        }.getOrDefault(-1)
        appendLine("Servizi auto vecchio tipo: $legacy")

        val bt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else true
        appendLine("Permesso Bluetooth: ${if (bt) "si" else "NO"}")
        appendLine("Adattatore scelto: ${Connessione.indirizzoScelto(ctx) ?: "nessuno"}")
        appendLine("Stato connessione: ${Connessione.testo}")
        if (Connessione.versione.isNotEmpty()) appendLine("ELM: ${Connessione.versione}")
        appendLine()
        appendLine("--- Eventi (ultimi $MAX) ---")
        appendLine(eventi(ctx))
    }

    fun condividi(ctx: Context) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Diagnosi Megane")
            putExtra(Intent.EXTRA_TEXT, rapporto(ctx))
        }
        ctx.startActivity(Intent.createChooser(i, "Condividi diagnosi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
