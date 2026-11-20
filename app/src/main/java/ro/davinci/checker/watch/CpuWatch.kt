package ro.davinci.checker.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * „Paznicul CPU”: monitorizarea din fundal, pornită din comutatorul din card.
 *
 * Cât timp e pornit, un serviciu în prim-plan citește consumul o dată la 45 de
 * secunde și anunță aplicațiile care trec de [THRESHOLD_PERCENT] deși nu sunt pe
 * ecran. Prețul e notificarea permanentă, pe care Android o cere pentru orice
 * muncă în fundal — fără ea sistemul ar opri monitorizarea la câteva minute.
 *
 * Setările stau în același fișier `checker_prefs` ca restul aplicației, ca
 * serviciul și interfața să vadă aceeași valoare.
 */
object CpuWatch {

    /** Cheia din `checker_prefs`. */
    const val KEY_ENABLED = "cpu_watch"

    /** Canalul de notificări; unul singur, ca utilizatorul să-l poată opri ușor. */
    const val CHANNEL_ID = "cpu_watch"

    const val ONGOING_ID = 4101
    private const val STOPPED_ID = 4102
    private const val ALERT_ID = 4200

    /** Cât la sută dintr-un nucleu trebuie să ardă o aplicație ca să merite un anunț. */
    const val THRESHOLD_PERCENT = 25f

    /** O eșantionare la 45 de secunde: suficient de des ca să prinzi un proces scăpat. */
    const val INTERVAL_MS = 45_000L

    /** Același pachet nu e anunțat mai des de atât. */
    const val COOLDOWN_MS = 20 * 60 * 1000L

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /** Pornește sau oprește serviciul și ține minte alegerea. */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        val intent = Intent(context, CpuWatchService::class.java)
        if (enabled) {
            // Pornirea se face din interfață (aplicația e în prim-plan), deci
            // Android permite un serviciu în prim-plan.
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.stopService(intent)
        }
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Consum în fundal",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Anunță aplicațiile care consumă CPU cât timp nu sunt pe ecran."
        }
        manager.createNotificationChannel(channel)
    }

    internal fun stoppedId(): Int = STOPPED_ID

    internal fun alertId(packageName: String): Int =
        ALERT_ID + (packageName.hashCode() and 0xFF)

    private fun prefs(context: Context) =
        context.getSharedPreferences("checker_prefs", Context.MODE_PRIVATE)
}
