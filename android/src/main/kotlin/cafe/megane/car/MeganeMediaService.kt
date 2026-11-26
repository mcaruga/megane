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
            notifyChildrenChanged(RADICE)
            notifyChildrenChanged(CRUSCOTTO)
            notifyChildrenChanged(GT)
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
                // lo stato sta accanto alla scheda: 🟢 motore acceso, 🟡 quadro, ⚪ spento
                val pallino = when { st?.motoreAcceso == true -> "🟢"; st?.quadroAcceso == true -> "🟡"; else -> "⚪" }
                out += cartella(CRUSCOTTO, "$pallino Cruscotto", griglia = true)
                out += cartella(GT, "GT", griglia = true)
                out += cartella(CODICI, "Codici guasto", griglia = false)
            }
            GT -> {
                st?.gtRichiesto()
                // lo schermo pista in grande si apre toccando la prima voce
                out += voce(PISTA, "Schermo pista", "Tocca: giri, velocita, turbo, olio, acqua, batteria, benzina",
                    GaugeProvider.uriPista(st))
                (StatoVeicolo.GT_PIDS + StatoVeicolo.GT_CALCOLATI).forEach { pid ->
                    val def = st?.catalogo?.pids?.firstOrNull { it.pid == pid } ?: return@forEach
                    val v = st.valore(pid)
                    out += voce(pid, def.traduzione, testoValore(v, def.unita), GaugeProvider.uriParametro(pid, v))
                }
            }
            CRUSCOTTO -> {
                val k = when { st?.motoreAcceso == true -> 2; st?.quadroAcceso == true -> 1; else -> 0 }
                // a motore acceso il riquadro di stato non serve (c'e il pallino sulla scheda);
                // negli altri casi resta, perche porta il testo di collegamento/errore
                if (k != 2) out += voce("conn", titoloStato(st), Connessione.testo, GaugeProvider.uriStato(k))
                st?.parametriScelti?.forEach { def ->
                    val v = st.valore(def.pid)
                    out += voce(def.pid, def.traduzione, testoValore(v, def.unita), GaugeProvider.uriParametro(def.pid, v))
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
        val def = st?.catalogo?.pids?.firstOrNull { it.pid == id }
        val meta = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_ALBUM, "Megane")
        if (id == PISTA) {
            // schermo pista: l'immagine grande e la "copertina"
            st?.gtRichiesto()
            val uri = GaugeProvider.uriPista(st).toString()
            meta.putString(MediaMetadata.METADATA_KEY_TITLE, "Pista")
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                    listOfNotNull(st?.valore("0C")?.let { "%.0f giri".format(it) },
                        st?.valore("0D")?.let { "%.0f km/h".format(it) }).joinToString("  "))
                .putString(MediaMetadata.METADATA_KEY_ART_URI, uri)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, uri)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, uri)
        } else {
            val (titolo, testo) = when {
                def != null && st != null -> testoValore(st.valore(def.pid), def.unita) to def.traduzione
                else -> titoloStato(st) to Connessione.testo
            }
            meta.putString(MediaMetadata.METADATA_KEY_TITLE, titolo)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, testo)
        }
        sessione.setMetadata(meta.build())
        sessione.setPlaybackState(stato())
    }

    private fun stato() = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PAUSED, 0, 0f)
        .setActions(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY)
        .build()

    /** Lo stile (griglia o elenco) dei contenuti si dichiara sulla cartella. */
    private fun cartella(id: String, titolo: String, griglia: Boolean) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(titolo)
            .setExtras(Bundle().apply {
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", if (griglia) 2 else 1)
            }).build(),
        MediaBrowser.MediaItem.FLAG_BROWSABLE)

    private fun voce(id: String, titolo: String, sotto: String, icona: android.net.Uri? = null) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(titolo).setSubtitle(sotto)
            .apply { if (icona != null) setIconUri(icona) }.build(),
        MediaBrowser.MediaItem.FLAG_PLAYABLE)

    companion object {
        private const val RADICE = "radice"
        private const val CRUSCOTTO = "cruscotto"
        private const val CODICI = "codici"
        private const val GT = "gt"
        private const val PISTA = "pista"
    }
}
