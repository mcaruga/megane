package cafe.megane.car

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService

/**
 * Megane su Android Auto come "app multimediale".
 *
 * Android Auto mostra le app per auto (template) solo se installate dal Play Store,
 * mentre accetta le app multimediali installate da APK. Qui non c'e nessun audio:
 * l'elenco "brani" e il cruscotto, ogni riga un parametro col suo valore,
 * aggiornato ogni 2 secondi. Toccare una riga la mostra in grande.
 * Sola lettura, come il resto dell'app: nessun comando verso l'auto.
 */
class MeganeMediaService : MediaBrowserService() {

    private lateinit var sessione: MediaSession
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var scelto: String? = null

    private val giro = object : Runnable {
        override fun run() {
            notifyChildrenChanged(CRUSCOTTO)
            notifyChildrenChanged(CODICI)
            aggiornaGrande()
            h.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { Diagnosi.evento(this, "AUTO: Android Auto ha aperto Megane (multimediale)") }
        runCatching { DtcItaliano.inizializza(applicationContext) }
        sessione = MediaSession(this, "Megane").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    scelto = mediaId
                    aggiornaGrande()
                }
                override fun onPlay() { aggiornaGrande() }
            })
            setPlaybackState(stato())
            isActive = true
        }
        sessionToken = sessione.sessionToken
        // senza servizio di lettura non arrivano dati
        runCatching { PollingService.avvia(this) }
        h.postDelayed(giro, 2000)
    }

    override fun onDestroy() {
        h.removeCallbacks(giro)
        sessione.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(RADICE, Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)   // elenco, non griglia
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
        })

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val st = StatoApp.stato(applicationContext)
        val out = mutableListOf<MediaBrowser.MediaItem>()
        when (parentId) {
            // le cartelle in cima diventano schede su R-Link2
            RADICE -> {
                out += cartella(CRUSCOTTO, "Cruscotto")
                out += cartella(CODICI, "Codici guasto")
            }
            CRUSCOTTO -> {
                out += voce("conn", titoloStato(st), Connessione.testo)
                st?.parametriScelti?.forEach { def ->
                    out += voce(def.pid, def.traduzione, testoValore(st.valore(def.pid), def.unita))
                }
            }
            CODICI -> {
                val codici = st?.dtc.orEmpty()
                if (codici.isEmpty()) out += voce("nodtc", "Nessun codice guasto attivo",
                    if (st?.quadroAcceso == true) "Lettura dalla centralina" else "Si legge a quadro acceso")
                codici.forEach { out += voce("dtc_$it", it, DtcItaliano.spiega(it)) }
            }
        }
        result.sendResult(out)
    }

    private fun titoloStato(st: StatoVeicolo?) = when {
        st?.motoreAcceso == true -> "Collegato: motore acceso"
        st?.quadroAcceso == true -> "Collegato: quadro acceso, motore fermo"
        Connessione.collegato -> "vLinker collegato: motore spento"
        else -> "vLinker non collegato"
    }

    private fun testoValore(v: Double?, unita: String) = v?.let { "%.1f %s".format(it, unita) } ?: "--"

    /** Riga toccata: valore in grande nella schermata "in riproduzione". */
    private fun aggiornaGrande() {
        val id = scelto ?: return
        val st = StatoApp.stato(applicationContext)
        val def = st?.parametriScelti?.firstOrNull { it.pid == id }
        val (titolo, testo) = when {
            def != null -> testoValore(st.valore(def.pid), def.unita) to def.traduzione
            else -> titoloStato(st) to Connessione.testo
        }
        sessione.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, titolo)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, testo)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Megane")
            .build())
        sessione.setPlaybackState(stato())
    }

    private fun stato() = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PAUSED, 0, 0f)
        .setActions(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY)
        .build()

    private fun cartella(id: String, titolo: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(titolo).build(),
        MediaBrowser.MediaItem.FLAG_BROWSABLE)

    private fun voce(id: String, titolo: String, sotto: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(titolo).setSubtitle(sotto).build(),
        MediaBrowser.MediaItem.FLAG_PLAYABLE)

    companion object {
        private const val RADICE = "radice"
        private const val CRUSCOTTO = "cruscotto"
        private const val CODICI = "codici"
    }
}
