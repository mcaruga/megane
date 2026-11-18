package cafe.megane.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Aggiornamenti dal server megane.omniking.cc.
 *
 * Il server pubblica solo cio che approvi nel gestionale: versions.txt elenca le
 * versioni (ver|data|note|sha256), megane-diag.apk e l'ultima.
 * L'app controlla all'apertura e ogni 30 minuti mentre il servizio gira:
 * nuova versione -> notifica -> tocco -> scarica, controlla lo SHA-256, installa.
 * Dal secondo aggiornamento in poi (Android 12+) l'installazione non chiede piu conferma.
 */
object Aggiornamenti {
    private const val BASE = "https://megane.omniking.cc/downloads/"
    private const val CANALE = "aggiornamenti"
    private const val NOTIFICA = 2
    private const val PREFS = "aggiornamenti"
    const val EXTRA_AGGIORNA = "aggiorna"

    data class Versione(val ver: String, val note: String, val sha: String)

    @Volatile var stato: String = ""; private set

    private fun versioneAttuale(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "0.0.0"

    private fun numeri(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }

    private fun piuNuova(a: String, b: String): Boolean {
        val x = numeri(a); val y = numeri(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Ultima versione pubblicata, o null se il server non risponde. Da chiamare fuori dal main thread. */
    fun ultima(): Versione? = runCatching {
        val c = URL(BASE + "versions.txt?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000; c.useCaches = false
        c.inputStream.bufferedReader().use { it.readText() }
            .lines().filter { it.isNotBlank() }.lastOrNull()
            ?.split("|")?.let { p -> Versione(p[0], p.getOrElse(2) { "" }, p.getOrElse(3) { "" }) }
    }.getOrNull()

    /** Se c'e una versione nuova manda la notifica (una volta per versione). Fuori dal main thread. */
    fun controlla(ctx: Context, forza: Boolean = false): Versione? {
        val v = ultima() ?: run { stato = "Server aggiornamenti non raggiungibile"; return null }
        val attuale = versioneAttuale(ctx)
        if (!piuNuova(v.ver, attuale)) { stato = "App aggiornata ($attuale)"; return null }
        stato = "Disponibile la versione ${v.ver} (hai la $attuale)"
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (forza || p.getString("notificata", null) != v.ver) {
            notifica(ctx, v)
            p.edit().putString("notificata", v.ver).apply()
        }
        return v
    }

    private fun notifica(ctx: Context, v: Versione) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANALE, "Aggiornamenti", NotificationManager.IMPORTANCE_DEFAULT))
        val apri = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, TelefonoActivity::class.java).putExtra(EXTRA_AGGIORNA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIFICA, android.app.Notification.Builder(ctx, CANALE)
            .setSmallIcon(R.drawable.ic_generico)
            .setContentTitle("Megane ${v.ver} disponibile")
            .setContentText(v.note.ifBlank { "Tocca per aggiornare" })
            .setContentIntent(apri)
            .setAutoCancel(true)
            .build())
    }

    /** Android chiede una volta il permesso "installa app sconosciute" per Megane. */
    fun puoInstallare(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun apriPermessoInstallazione(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Scarica l'APK, verifica lo SHA-256 contro versions.txt e avvia l'installazione. Fuori dal main thread. */
    fun scaricaEInstalla(ctx: Context): Result<Unit> = runCatching {
        val v = ultima() ?: error("Server aggiornamenti non raggiungibile")
        require(piuNuova(v.ver, versioneAttuale(ctx))) { "Hai gia l'ultima versione" }
        stato = "Scarico la ${v.ver}..."
        val file = File(ctx.cacheDir, "aggiornamento.apk")
        val c = URL(BASE + "megane-diag.apk?v=" + v.ver).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000; c.useCaches = false
        val md = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { inp ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    md.update(buf, 0, n); out.write(buf, 0, n)
                }
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (v.sha.isNotEmpty() && !sha.equals(v.sha, ignoreCase = true)) {
            file.delete()
            error("File scaricato non valido (SHA-256 diverso): riprova")
        }
        stato = "Installo la ${v.ver}..."
        installa(ctx, file)
    }

    private fun installa(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val par = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = pi.createSession(par)
        pi.openSession(id).use { s ->
            s.openWrite("megane.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val esito = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, EsitoInstallazione::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(esito.intentSender)
        }
    }

    class EsitoInstallazione : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // prima volta: Android mostra "Vuoi aggiornare questa app?"
                    @Suppress("DEPRECATION")
                    val conferma = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    conferma?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
                }
                PackageInstaller.STATUS_SUCCESS -> stato = "Aggiornata"
                else -> {
                    stato = "Installazione non riuscita ($st): " +
                        (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
                    runCatching { Diagnosi.evento(ctx, "UPDATE: $stato") }
                }
            }
        }
    }
}
