package ro.davinci.checker.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import ro.davinci.checker.shell.ShellBackend

/**
 * Scanează pachetele instalate și, dacă există un backend de shell, corelează
 * procesele care rulează cu pachetele lor (prin UID, la fel ca `ps` + /proc).
 *
 * Categoriile cerute:
 *  - sistem  -> ApplicationInfo.FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP
 *  - utilizator -> restul
 *  - înghețat -> ApplicationInfo.enabled == false (pm disable-user)
 *  - rulează  -> are cel puțin un PID în `ps`
 *  - fundal   -> rulează, dar nu e pachetul din prim-plan
 *
 * În plus, fiecare intrare primește informații despre aplicație (versiune de
 * SDK, dată de instalare, sursă, permisiuni) și un verdict de siguranță la
 * oprire, calculat de [SafetyAdvisor].
 */
class AppScanner(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val safetyAdvisor = SafetyAdvisor(context)
    private val probe = LiveProbe()

    private val foregroundRegex =
        Regex("""([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""")
    private val servicePackageRegex = Regex("""^\s*packageName=(\S+)\s*$""")

    /** Nivelul de protecție al fiecărei permisiuni, memorat per scanare. */
    private val permissionLevels = HashMap<String, Int>()

    fun scan(shell: ShellBackend?): ScanResult {
        val packages = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(
                PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.GET_PERMISSIONS,
            )
        }.getOrDefault(emptyList())

        var warning: String? = null
        val live = if (shell != null) {
            probe.sample(shell).also { sample ->
                if (sample.error != null) warning = sample.error
            }
        } else {
            LiveSample()
        }
        val running = live.processes

        val foreground = if (shell != null && live.error == null) {
            readForeground(shell)
        } else {
            null
        }

        val foregroundServices = if (shell != null && live.error == null) {
            readForegroundServices(shell)
        } else {
            emptySet()
        }

        val roles = runCatching { safetyAdvisor.roles() }.getOrDefault(SafetyAdvisor.Roles())
        val launchIntents = launchablePackages()
        permissionLevels.clear()

        val apps = packages.mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            if (appInfo.packageName == context.packageName) return@mapNotNull null

            val uid = appInfo.uid
            val proc = running[uid]
            val permissions = info.requestedPermissions?.size ?: 0

            var entry = AppEntry(
                packageName = appInfo.packageName,
                label = runCatching { appInfo.loadLabel(pm).toString() }
                    .getOrDefault(appInfo.packageName),
                uid = uid,
                isSystem = (appInfo.flags and (
                    ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    )) != 0,
                isFrozen = !appInfo.enabled,
                versionName = info.versionName ?: "—",
                apkPaths = buildList {
                    appInfo.sourceDir?.let(::add)
                    appInfo.splitSourceDirs?.let { addAll(it) }
                }.distinct(),
                updatedAt = info.lastUpdateTime,
                pids = proc?.pids ?: emptyList(),
                rssKb = proc?.rssKb ?: 0,
                cpuPercent = proc?.cpuPercent ?: 0f,
                installedAt = info.firstInstallTime,
                targetSdk = appInfo.targetSdkVersion,
                minSdk = appInfo.minSdkVersion,
                installer = installerOf(info),
                permissionCount = permissions,
                dangerousPermissions = dangerousCount(info, permissions),
                hasLaunchIntent = appInfo.packageName in launchIntents,
                isPersistent = (appInfo.flags and ApplicationInfo.FLAG_PERSISTENT) != 0,
                hasForegroundService = appInfo.packageName in foregroundServices,
            )
            val verdict = safetyAdvisor.evaluate(entry, roles)
            entry = entry.copy(safety = verdict.level, safetyReason = verdict.reason)
            entry
        }

        // UID-urile partajate (system_server, media, radio) raportează aceleași
        // procese pentru zeci de pachete; memoria lor nu se poate pune pe seama
        // unui singur pachet, deci o marcăm ca partajată.
        val uidCounts = apps.groupingBy { it.uid }.eachCount()
        val withShared = apps.map { app ->
            if ((uidCounts[app.uid] ?: 1) > 1) app.copy(sharedUid = true) else app
        }

        return ScanResult(
            apps = withShared.sortedWith(
                compareBy<AppEntry> { it.isFrozen }
                    .thenByDescending { it.ownRssKb }
                    .thenBy { it.label.lowercase() },
            ),
            foregroundPackage = foreground,
            shellKind = shell?.kind?.name,
            warning = warning?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Doar procesele și starea sistemului, fără `pm list packages`.
     * Folosită de tick-ul live, ca să nu rescaneze 551 pachete la fiecare 2 secunde.
     */
    fun sample(shell: ShellBackend): LiveSample = probe.sample(shell)

    /** Doar pachetul din prim-plan, pentru reîmprospătarea periodică. */
    fun foreground(shell: ShellBackend): String? = readForeground(shell)

    /** Pachetele care au o activitate de lansare (apar în sertarul de aplicații). */
    private fun launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun installerOf(info: android.content.pm.PackageInfo): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(info.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(info.packageName)
        }
    }.getOrNull()

    /**
     * Câte dintre permisiunile cerute sunt „periculoase” (runtime).
     * Nivelurile se memorează: sunt câteva sute de permisiuni distincte.
     */
    private fun dangerousCount(info: android.content.pm.PackageInfo, total: Int): Int {
        if (total <= 0) return 0
        val requested = info.requestedPermissions ?: return 0
        var dangerous = 0
        requested.forEach { name ->
            val level = permissionLevels.getOrPut(name) {
                runCatching { pm.getPermissionInfo(name, 0).protectionLevel }.getOrDefault(0)
            }
            if ((level and android.content.pm.PermissionInfo.PROTECTION_DANGEROUS) != 0) {
                dangerous++
            }
        }
        return dangerous
    }

    /**
     * Pachetele cu serviciu în prim-plan, dintr-o singură citire:
     * `dumpsys activity services` listează `packageName=...` și, pentru
     * serviciile în prim-plan, `isForeground=true`.
     */
    private fun readForegroundServices(shell: ShellBackend): Set<String> {
        val result = shell.exec(
            "dumpsys activity services 2>/dev/null | grep -E '^ *packageName=|isForeground=true'",
            25_000,
        )
        if (result.out.isBlank()) return emptySet()

        val packages = HashSet<String>()
        var current: String? = null
        result.out.lineSequence().forEach { line ->
            val match = servicePackageRegex.find(line)
            if (match != null) {
                current = match.groupValues[1]
            } else if (line.contains("isForeground=true")) {
                current?.let(packages::add)
            }
        }
        return packages
    }

    /**
     * Pachetul din prim-plan: `dumpsys activity activities` conține linia
     * `topResumedActivity=ActivityRecord{... u0 com.foo/.MainActivity ...}`.
     */
    private fun readForeground(shell: ShellBackend): String? {
        val result = shell.exec(
            "dumpsys activity activities 2>/dev/null | grep -m 2 -E 'topResumedActivity|mResumedActivity'",
            20_000,
        )
        if (result.out.isBlank()) return null

        val line = result.out.lineSequence()
            .firstOrNull { it.contains("ResumedActivity") }
            ?: return null

        return foregroundRegex.find(line)?.groupValues?.get(1)
    }
}