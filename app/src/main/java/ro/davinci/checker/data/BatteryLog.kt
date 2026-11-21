package ro.davinci.checker.data

import android.content.Context
import ro.davinci.checker.shell.ShellBackend

/**
 * Ține minte când a început încărcarea și când a început descărcarea.
 *
 * Android nu expune „ora la care s-a băgat încărcătorul" ca API, dar o ține în
 * istoricul de baterie, pe care [BatteryHistory] îl citește. Sunt două surse,
 * ambele folosite:
 *
 *  - **istoricul sistemului** — ora și procentul exacte ale tranziției, chiar
 *    dacă aplicația dormea la momentul respectiv;
 *  - **observația proprie** — fiecare eșantion care vede statusul schimbat
 *    completează ce lipsește din istoric (dacă tamponul e plin sau dezactivat).
 *
 * Când amândouă există pentru aceeași tranziție, câștigă istoricul: e exact, pe
 * când observația poate fi cu până la un eșantion mai târziu.
 *
 * Starea se ține în `checker_prefs`, ca să supraviețuiască repornirii aplicației.
 * Se scrie doar când se schimbă ceva: la 2 secunde, o scriere ar fi risipă.
 */
class BatteryLog(context: Context) {

    private val prefs = context.getSharedPreferences("checker_prefs", Context.MODE_PRIVATE)

    /**
     * Citește istoricul sistemului și completează orele reținute.
     *
     * Durează ~1,3 s, deci se rulează cel mult o dată la [SEED_EVERY_MS]. Nu
     * aruncă: dacă shell-ul nu răspunde, rămân valorile deja știute.
     */
    fun seedFrom(shell: ShellBackend, now: Long = System.currentTimeMillis()) {
        val last = prefs.getLong(KEY_SEED_AT, 0L)
        if (now - last < SEED_EVERY_MS) return

        val text = runCatching { shell.exec(BatteryHistory.COMMAND, 25_000).out }
            .getOrNull()
            .orEmpty()
        if (text.isBlank()) return

        prefs.edit().putLong(KEY_SEED_AT, now).apply()
        seed(BatteryHistory.parse(text, now), now)
    }

    /** Completează orele cu tranzițiile venite din istoric. */
    fun seed(events: BatteryEvents, now: Long = System.currentTimeMillis()) {
        if (events.chargeAt <= 0L && events.dischargeAt <= 0L && events.fullAt <= 0L) return

        val chargeAt = prefs.getLong(KEY_CHARGE_AT, 0L)
        val chargePercent = prefs.getInt(KEY_CHARGE_PERCENT, -1)
        val fullAt = prefs.getLong(KEY_FULL_AT, 0L)
        val dischargeAt = prefs.getLong(KEY_DISCHARGE_AT, 0L)
        val dischargePercent = prefs.getInt(KEY_DISCHARGE_PERCENT, -1)

        // O tranziție din istoric înlocuiește ce e reținut dacă e mai nouă sau
        // dacă observația proprie era a aceluiași eveniment, luată cu câteva
        // secunde mai târziu.
        fun accept(seeded: Long, stored: Long): Boolean =
            seeded > 0L && (stored <= 0L || seeded >= stored || stored - seeded < SAME_EVENT_MS)

        val newChargeAt = if (accept(events.chargeAt, chargeAt)) events.chargeAt else chargeAt
        val chargeChanged = newChargeAt != chargeAt
        val newChargePercent = if (chargeChanged) events.chargePercent else chargePercent

        // „Plină" aparține sesiunii de încărcare curente: dacă istoricul aduce o
        // încărcare nouă, un „plină" mai vechi nu are ce căuta lângă ea.
        val sessionFull = if (newChargeAt > 0L && events.fullAt >= newChargeAt) {
            events.fullAt
        } else {
            0L
        }
        val newFullAt = when {
            chargeChanged -> sessionFull
            sessionFull > fullAt -> sessionFull
            else -> fullAt
        }

        var newDischargeAt = if (accept(events.dischargeAt, dischargeAt)) {
            events.dischargeAt
        } else {
            dischargeAt
        }
        var newDischargePercent = if (newDischargeAt != dischargeAt) {
            events.dischargePercent
        } else {
            dischargePercent
        }

        // Descărcarea de dinaintea ultimei încărcări s-a încheiat; următoarea
        // scoatere din priză o va înlocui.
        if (newChargeAt > 0L && newDischargeAt > 0L && newDischargeAt < newChargeAt) {
            newDischargeAt = 0L
            newDischargePercent = -1
        }

        val changed = chargeChanged ||
            newFullAt != fullAt ||
            newDischargeAt != dischargeAt
        if (!changed) return

        write(
            chargeAt = newChargeAt,
            chargePercent = newChargePercent,
            fullAt = newFullAt,
            dischargeAt = newDischargeAt,
            dischargePercent = newDischargePercent,
        )
    }

    /** Completează eșantionul cu orele reținute; întoarce tot `null` dacă nu are date. */
    fun update(stats: BatteryStats?, now: Long = System.currentTimeMillis()): BatteryStats? {
        if (stats == null || !stats.valid) return stats

        var chargeAt = prefs.getLong(KEY_CHARGE_AT, 0L)
        var chargePercent = prefs.getInt(KEY_CHARGE_PERCENT, -1)
        var fullAt = prefs.getLong(KEY_FULL_AT, 0L)
        var dischargeAt = prefs.getLong(KEY_DISCHARGE_AT, 0L)
        var dischargePercent = prefs.getInt(KEY_DISCHARGE_PERCENT, -1)

        val wasCharging = prefs.getBoolean(KEY_WAS_CHARGING, false)
        val isCharging = stats.charging
        var changed = false

        if (isCharging) {
            // Încărcare nouă: fie prima observație a sesiunii, fie o sesiune
            // veche (din istoric) care nu are legătură cu cea de acum.
            val stale = chargeAt <= 0L || now - chargeAt > CHARGE_SESSION_MAX_MS
            if (!wasCharging && stale) {
                chargeAt = now
                chargePercent = stats.percent
                fullAt = 0L
                changed = true
            }
            if (stats.status == BatteryStats.Status.FULL && fullAt <= 0L) {
                fullAt = now
                changed = true
            }
            if (!wasCharging) {
                prefs.edit().putBoolean(KEY_WAS_CHARGING, true).apply()
            }
        } else if (stats.status == BatteryStats.Status.DISCHARGING) {
            // Descărcare nouă: fie prima observație, fie imediat după ce
            // încărcătorul a fost scos (statusul de dinainte era „se încarcă").
            if (wasCharging) {
                dischargeAt = now
                dischargePercent = stats.percent
                changed = true
                prefs.edit().putBoolean(KEY_WAS_CHARGING, false).apply()
            } else if (dischargeAt <= 0L) {
                dischargeAt = now
                dischargePercent = stats.percent
                changed = true
            }
        }

        if (changed) {
            write(chargeAt, chargePercent, fullAt, dischargeAt, dischargePercent)
        }

        return stats.copy(
            chargeStartedAt = chargeAt,
            chargeStartedPercent = chargePercent,
            chargedFullAt = fullAt,
            dischargeStartedAt = dischargeAt,
            dischargeStartedPercent = dischargePercent,
        )
    }

    private fun write(
        chargeAt: Long,
        chargePercent: Int,
        fullAt: Long,
        dischargeAt: Long,
        dischargePercent: Int,
    ) {
        prefs.edit()
            .putLong(KEY_CHARGE_AT, chargeAt)
            .putInt(KEY_CHARGE_PERCENT, chargePercent)
            .putLong(KEY_FULL_AT, fullAt)
            .putLong(KEY_DISCHARGE_AT, dischargeAt)
            .putInt(KEY_DISCHARGE_PERCENT, dischargePercent)
            .apply()
    }

    private companion object {
        const val KEY_CHARGE_AT = "battery_charge_at"
        const val KEY_CHARGE_PERCENT = "battery_charge_percent"
        const val KEY_FULL_AT = "battery_full_at"
        const val KEY_DISCHARGE_AT = "battery_discharge_at"
        const val KEY_DISCHARGE_PERCENT = "battery_discharge_percent"
        const val KEY_WAS_CHARGING = "battery_was_charging"
        const val KEY_SEED_AT = "battery_seed_at"

        /** O tranziție și observația ei se consideră același eveniment sub 10 min. */
        const val SAME_EVENT_MS = 10 * 60_000L

        /** Peste atât, o încărcare reținută nu mai poate fi cea de acum. */
        const val CHARGE_SESSION_MAX_MS = 6 * 60 * 60_000L

        /** Cât de des se recitește istoricul sistemului (e o comandă de ~1,3 s). */
        const val SEED_EVERY_MS = 15 * 60_000L
    }
}