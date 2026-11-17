package cafe.megane.car

import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/** Icona per riquadro del cruscotto R-Link2, per PID. */
object Icone {

    fun perPid(ctx: CarContext, pid: String): CarIcon {
        val res = when (pid) {
            "0C" -> R.drawable.ic_rpm
            "05" -> R.drawable.ic_temp_acqua
            "5C" -> R.drawable.ic_temp_olio
            "0F" -> R.drawable.ic_temp_aria
            "0B", StatoVeicolo.DERIVATO_BOOST -> R.drawable.ic_turbo
            "42" -> R.drawable.ic_batteria
            "04", "11" -> R.drawable.ic_carico
            "0D" -> R.drawable.ic_velocita
            "10", "2F" -> R.drawable.ic_consumo
            "06", "07" -> R.drawable.ic_miscela
            "0E" -> R.drawable.ic_anticipo
            else -> R.drawable.ic_generico
        }
        return CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()
    }
}
