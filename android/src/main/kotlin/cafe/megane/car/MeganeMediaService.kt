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
import cafe.megane.core.SessionePista

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
            notifyChildrenChanged(PISTA_MOD)
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
                    when (mediaId) {
                        P_AVVIA -> {
                            if (PistaStato.gpsAttivo) { PistaGpsService.ferma(this@MeganeMediaService); PistaStato.avviso("Sessione ferma") }
                            else if (!PistaGpsService.permessoOk(this@MeganeMediaService))
                                PistaStato.avviso("Manca il permesso posizione: apri l'app Megane sul telefono")
                            else { PistaGpsService.avvia(this@MeganeMediaService); PistaStato.avviso("Cerco il GPS...") }
                            scelto = P_MAPPA
                        }
                        P_TRAG -> {
                            PistaStato.avviso(
                                if (!PistaStato.gpsVivo()) "Prima avvia la sessione e aspetta il GPS"
                                else if (PistaStato.sessione.impostaTraguardo()) "Traguardo messo. Il cronometro parte al primo passaggio"
                                else "Serve qualche metro di marcia dritta per capire la direzione")
                            scelto = P_MAPPA
                        }
                        P_AZZERA -> { PistaStato.sessione.azzera(); PistaStato.avviso("Sessione azzerata"); scelto = P_MAPPA }
                    }
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
                out += cartella(PISTA_MOD, "Modalita pista (circuito e cronometro)", griglia = false)
                (StatoVeicolo.GT_PIDS + StatoVeicolo.GT_CALCOLATI).forEach { pid ->
                    val def = st?.catalogo?.pids?.firstOrNull { it.pid == pid } ?: return@forEach
                    if (!st.disponibile(def)) return@forEach      // l'auto non lo espone: niente riquadro vuoto
                    val v = st.valore(pid)
                    out += voce(pid, def.traduzione, testoValore(v, def.unita), GaugeProvider.uriParametro(pid, v))
                }
            }
            CRUSCOTTO -> {
                // niente riquadro di stato: lo stato e il pallino sulla scheda.
                // Il testo di collegamento/errore resta nell'app sul telefono.
                st?.parametriScelti?.forEach { def ->
                    val v = st.valore(def.pid)
                    out += voce(def.pid, def.traduzione, testoValore(v, def.unita), GaugeProvider.uriParametro(def.pid, v))
                }
            }
            PISTA_MOD -> {
                val s = PistaStato.sessione
                fun t(ms: Long?) = ms?.let { SessionePista.formatta(it) } ?: "--"
                out += voce(P_MAPPA, "Circuito", PistaStato.stato(), GaugeProvider.uriCircuito())
                out += voce(P_AVVIA, if (PistaStato.gpsAttivo) "Ferma sessione" else "Avvia sessione",
                    "GPS del telefono. Una volta, poi guida")
                out += voce(P_TRAG, "Traguardo qui", "Metti la linea di partenza dove sei ora, in marcia")
                out += voce("p_giro", "Giro in corso", t(PistaStato.tempoGiroMs()))
                out += voce("p_delta", "Distacco dal migliore",
                    (if (PistaStato.gpsVivo()) s.delta() else null)?.let { (if (it > 0) "+" else "") + SessionePista.formatta(it) } ?: "--")
                out += voce("p_ultimo", "Ultimo giro", t(s.ultimo?.tempoMs))
                out += voce("p_migl", "Miglior giro", t(s.migliore?.tempoMs))
                out += voce("p_n", "Giri completati", "${s.giri.size}")
                out += voce(P_AZZERA, "Azzera sessione", "Cancella giri e disegno del circuito")
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
        if (id == P_MAPPA) {
            val uri = GaugeProvider.uriCircuito().toString()
            val giri = PistaStato.sessione.giri
            meta.putString(MediaMetadata.METADATA_KEY_TITLE, "Pista")
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                    PistaStato.avvisoRecente()
                        ?: giri.lastOrNull()?.let { "Ultimo ${SessionePista.formatta(it.tempoMs)}  ·  giri ${giri.size}" }
                        ?: PistaStato.stato())
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
        private const val PISTA_MOD = "pistamod"
        private const val P_MAPPA = "p_mappa"
        private const val P_AVVIA = "p_avvia"
        private const val P_TRAG = "p_trag"
        private const val P_AZZERA = "p_azzera"
    }
}
