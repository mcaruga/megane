package cafe.megane.car

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Registrazione CAN passiva da 60 s, avviabile anche dal display R-Link2.
 * Solo ascolto: i PID si fermano e dopo l'adattatore si ricollega da solo.
 */
object RegistrazioneCan {
    @Volatile var inCorso = false
    @Volatile private var fineMs = 0L

    fun avvia(ctx: Context, st: StatoVeicolo) {
        if (inCorso || st.modalita != StatoVeicolo.Modalita.PID) return
        inCorso = true
        fineMs = System.currentTimeMillis() + 60_000
        st.riepilogo.azzera()
        st.modalita = StatoVeicolo.Modalita.CAN_MONITOR
        Handler(Looper.getMainLooper()).postDelayed({
            st.modalita = StatoVeicolo.Modalita.PID
            inCorso = false
            // dopo ATMA l'adattatore e in modalita monitor: riparte pulito
            PollingService.avvia(ctx.applicationContext, riprova = true)
        }, 60_000)
    }

    fun stato(st: StatoVeicolo?): String {
        if (st == null) return "-"
        val resto = ((fineMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return if (inCorso) "Registro il bus... $resto s (${st.riepilogo.totale()} messaggi, ${st.riepilogo.quantiId()} ID)"
        else if (st.riepilogo.totale() > 0) "Registrazione pronta: ${st.riepilogo.totale()} messaggi, ${st.riepilogo.quantiId()} ID"
        else "Nessuna registrazione"
    }
}
