package ro.davinci.checker.data

import java.util.Locale

/**
 * Starea bateriei, citită din `/sys/class/power_supply/battery` (sau
 * `mmi_battery`, pe telefoanele Motorola) în același apel ca restul statisticilor.
 *
 * De ce din `sysfs` și nu din `BatteryManager`: acolo nu există capacitatea de
 * proiectare, capacitatea reală, curentul sau numărul de cicluri. Iar „când a
 * început încărcarea” nu se poate afla din nicio sursă a sistemului — se reține
 * la prima eșantionare care vede statusul schimbat (vezi [BatteryLog]).
 */
data class BatteryStats(
    /** Procentul afișat de sistem, 0..100. `-1` dacă nu s-a putut citi. */
    val percent: Int = -1,
    val status: Status = Status.UNKNOWN,
    /** Capacitatea de proiectare, în mAh — cea „din fabrică”. */
    val designMah: Int = 0,
    /** Capacitatea maximă măsurată acum, în mAh (scade cu uzura). */
    val fullMah: Int = 0,
    /** Curentul în mA; negativ la descărcare, pozitiv la încărcare. */
    val currentMa: Int = 0,
    val tempC: Float = 0f,
    val cycles: Int = 0,
    val health: String = "",

    /** Începutul încărcării curente (sau al ultimei), în epoch ms. */
    val chargeStartedAt: Long = 0L,
    /** Procentul de la care a pornit încărcarea. */
    val chargeStartedPercent: Int = -1,
    /** Când bateria a ajuns la 100% în încărcarea curentă (sau ultima). */
    val chargedFullAt: Long = 0L,
    /** Începutul descărcării curente (sau al ultimei), în epoch ms. */
    val dischargeStartedAt: Long = 0L,
    /** Procentul de la care a pornit descărcarea. */
    val dischargeStartedPercent: Int = -1,
) {

    enum class Status { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

    val valid: Boolean get() = percent in 0..100

    val charging: Boolean get() = status == Status.CHARGING || status == Status.FULL

    val statusLabel: String
        get() = when (status) {
            Status.CHARGING -> "se încarcă"
            Status.DISCHARGING -> "se descarcă"
            Status.FULL -> "plină"
            Status.NOT_CHARGING -> "nu se încarcă"
            Status.UNKNOWN -> "stare necunoscută"
        }

    /** Capacitatea din fabrică, ex. „7000 mAh”. */
    val designLabel: String
        get() = if (designMah > 0) "$designMah mAh" else "—"

    /** Cât la sută din capacitatea de proiectare mai ține bateria. */
    val healthPercent: Int
        get() = if (designMah <= 0 || fullMah <= 0) 0
        else (fullMah * 100.0 / designMah).toInt().coerceIn(0, 100)

    val fullLabel: String
        get() = if (fullMah > 0) {
            if (healthPercent > 0) "$fullMah mAh ($healthPercent%)" else "$fullMah mAh"
        } else {
            "—"
        }

    val currentLabel: String
        get() = if (currentMa == 0) "—" else "$currentMa mA"

    val tempLabel: String
        get() = if (tempC <= 0f) "—" else "%.1f °C".format(Locale.ROOT, tempC).replace('.', ',')

    /** „de 1 h 12 min” cât ține încărcarea, sau cât a ținut-o. */
    fun chargeDurationLabel(now: Long): String {
        if (chargeStartedAt <= 0L) return ""
        val end = if (chargedFullAt > 0L) chargedFullAt else now
        return formatDuration(end - chargeStartedAt)
    }

    /** „de 3 h 20 min” de când s-a deconectat încărcătorul. */
    fun dischargeDurationLabel(now: Long): String {
        if (dischargeStartedAt <= 0L) return ""
        return formatDuration(now - dischargeStartedAt)
    }

    companion object {

        /** „14:32” — ora la care s-a întâmplat ceva, în fusul telefonului. */
        fun clock(epochMs: Long): String =
            if (epochMs <= 0L) "—" else java.text.SimpleDateFormat("HH:mm", Locale.ROOT)
                .format(java.util.Date(epochMs))

        /** „1 h 12 min”, „12 min”, „sub 1 min”. */
        fun formatDuration(millis: Long): String {
            val minutes = (millis / 60_000L).coerceAtLeast(0)
            return when {
                minutes < 1 -> "sub 1 min"
                minutes < 60 -> "$minutes min"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }
    }
}