package ro.davinci.checker.watch

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.davinci.checker.MainActivity
import ro.davinci.checker.R
import ro.davinci.checker.data.AppLists
import ro.davinci.checker.data.AppScanner
import ro.davinci.checker.data.BatteryLog
import ro.davinci.checker.data.LiveProbe
import ro.davinci.checker.data.LiveSample
import ro.davinci.checker.shell.ShellBackend
import ro.davinci.checker.shell.ShellController

/**
 * Monitorizarea din fundal: un eșantion la 45 de secunde și un anunț pentru
 * fiecare aplicație care arde CPU deși nu e pe ecran.
 *
 * De ce serviciu în prim-plan și nu `WorkManager`: munca periodică obișnuită nu
 * poate coborî sub 15 minute, iar un proces scăpat se vede în câteva secunde.
 * De ce nu daemon nativ: tot ce e nevoie (`/proc`, `ps`, `cmd package`) se
 * citește prin shell, cu aceleași privilegii.
 *
 * Serviciul nu are buton de oprire în notificare: comutatorul din card rămâne
 * singurul loc de unde se pornește și se oprește, ca să nu existe două stări.
 */
class CpuWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val probe = LiveProbe()
    private val controller by lazy { ShellController(applicationContext) }
    private val scanner by lazy { AppScanner(applicationContext) }
    private val lists by lazy { AppLists(applicationContext) }
    private val batteryLog by lazy { BatteryLog(applicationContext) }

    private var job: Job? = null

    /** Câte eșantioane la rând a trecut fiecare pachet pragul. */
    private val strikes = HashMap<String, Int>()

    /** Când a fost anunțat ultima dată fiecare pachet. */
    private val lastAlert = HashMap<String, Long>()

    /** UID -> pachet, citit cu `cmd package list packages -U` și ținut 10 minute. */
    private var uidNames: Map<Int, String> = emptyMap()
    private var uidNamesAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CpuWatch.ensureChannel(this)
        startForeground(CpuWatch.ONGOING_ID, ongoingNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { controller.disconnect() }
        super.onDestroy()
    }

    private suspend fun loop() {
        var misses = 0
        while (currentCoroutineContext().isActive) {
            delay(CpuWatch.INTERVAL_MS)

            if (controller.backend == null) {
                val connected = runCatching { controller.autoConnect() }.getOrDefault(false)
                if (!connected) {
                    // Trei ratări la rând (aproape două minute) înseamnă că
                    // serverul adb sau Shizuku nu mai răspunde: nu are rost să
                    // țin telefonul treaz degeaba.
                    if (++misses >= 3) {
                        stopWithReason(
                            "Nu mai am acces la shell. Deschide aplicația ca să reconectezi.",
                        )
                        return
                    }
                    continue
                }
            }
            misses = 0
            val backend = controller.backend ?: continue

            val sample = withContext(Dispatchers.IO) {
                runCatching { probe.sample(backend) }.getOrNull()
            } ?: continue
            if (sample.error != null) continue

            // Aceleași ore de încărcare ca în interfață: altfel, cu aplicația
            // închisă, începutul încărcării s-ar pierde.
            withContext(Dispatchers.IO) {
                runCatching { batteryLog.seedFrom(backend) }
                runCatching { batteryLog.update(sample.stats.battery) }
            }

            val foreground = withContext(Dispatchers.IO) {
                runCatching { scanner.foreground(backend) }.getOrNull()
            }
            withContext(Dispatchers.IO) { evaluate(backend, sample, foreground) }
        }
    }

    /** Compară eșantionul cu pragul și anunță ce trebuie anunțat. */
    private fun evaluate(backend: ShellBackend, sample: LiveSample, foreground: String?) {
        val names = packageNames(backend)
        if (names.isEmpty()) return
        val white = lists.white()
        val now = SystemClock.elapsedRealtime()
        val hot = HashSet<String>()
        val self = packageName

        sample.processes.forEach { (uid, proc) ->
            // Sub 10 000 sunt procese de sistem: „System UI consumă 30%” nu e o
            // veste pe care utilizatorul o poate folosi.
            if (uid < 10_000) return@forEach
            if (proc.cpuPercent < CpuWatch.THRESHOLD_PERCENT) return@forEach
            val appPackage = names[uid] ?: return@forEach
            if (appPackage == self || appPackage == foreground) return@forEach
            if (appPackage in white) return@forEach

            hot += appPackage
            val count = (strikes[appPackage] ?: 0) + 1
            strikes[appPackage] = count
            // Un singur vârf nu e o problemă; două eșantioane la rând (90 s) da.
            if (count < 2) return@forEach

            val last = lastAlert[appPackage] ?: 0L
            if (now - last < CpuWatch.COOLDOWN_MS) return@forEach
            lastAlert[appPackage] = now
            alert(appPackage, labelOf(appPackage), proc.cpuPercent)
        }

        strikes.keys.retainAll(hot)
    }

    /**
     * Harta UID -> pachet. `cmd package list packages -U` răspunde în ~30 ms și
     * dă toate pachetele, deci nu are rost s-o repetăm la fiecare eșantion.
     */
    private fun packageNames(backend: ShellBackend): Map<Int, String> {
        val now = SystemClock.elapsedRealtime()
        if (uidNames.isNotEmpty() && now - uidNamesAt < 10 * 60_000L) return uidNames

        val result = runCatching {
            backend.exec("cmd package list packages -U", 20_000)
        }.getOrNull() ?: return uidNames
        if (result.out.isBlank()) return uidNames

        val map = HashMap<Int, String>(700)
        result.out.lineSequence().forEach { line ->
            val name = line.substringAfter("package:", "").substringBefore(' ').trim()
            if (name.isEmpty()) return@forEach
            line.substringAfter("uid:", "").trim().split(',').forEach { raw ->
                raw.toIntOrNull()?.let { map[it] = name }
            }
        }
        if (map.isNotEmpty()) {
            uidNames = map
            uidNamesAt = now
        }
        return map
    }

    private fun labelOf(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
            .toString()
    }.getOrDefault(packageName)

    private fun alert(packageName: String, label: String, percent: Float) {
        if (!canNotify()) return
        val text = "$label folosește ${"%.0f".format(percent)}% din CPU, " +
            "deși nu e pe ecran."
        val notification = Notification.Builder(this, CpuWatch.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle("Consum mare în fundal")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(CpuWatch.alertId(packageName), notification)
        }
    }

    private fun stopWithReason(message: String) {
        if (canNotify()) {
            val notification = Notification.Builder(this, CpuWatch.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_watch)
                .setContentTitle("Paznicul CPU s-a oprit")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(CpuWatch.stoppedId(), notification)
            }
        }
        stopSelf()
    }

    private fun ongoingNotification(): Notification =
        Notification.Builder(this, CpuWatch.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle("Paznicul CPU e pornit")
            .setContentText("Urmăresc aplicațiile care consumă CPU în fundal.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Fără permisiunea de notificări, `notify` ar arunca — o verificăm întâi. */
    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
