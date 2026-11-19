package ro.davinci.checker.data

import android.content.Context
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
 */
class AppScanner(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    private val psRegex = Regex("""^\s*(\d+)\s+(\d+)\s+(\S+)\s+(\d+)\s*$""")
    private val foregroundRegex =
        Regex("""([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""")

    fun scan(shell: ShellBackend?): ScanResult {
        val packages = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrDefault(emptyList())

        var warning: String? = null
        val running = if (shell != null) {
            readRunning(shell).also { snapshot ->
                if (snapshot.error != null) warning = snapshot.error
            }
        } else {
            RunningSnapshot.EMPTY
        }

        val foreground = if (shell != null && running.error == null) {
            readForeground(shell)
        } else {
            null
        }

        val apps = packages.mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            if (appInfo.packageName == context.packageName) return@mapNotNull null

            val uid = appInfo.uid
            val proc = running.byUid[uid]

            AppEntry(
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
            )
        }.sortedWith(
            compareBy<AppEntry> { it.isFrozen }.thenBy { it.label.lowercase() },
        )

        return ScanResult(
            apps = apps,
            foregroundPackage = foreground,
            shellKind = shell?.kind?.name,
            warning = warning?.takeIf { it.isNotBlank() },
        )
    }

    private fun readRunning(shell: ShellBackend): RunningSnapshot {
        val result = shell.exec("ps -A -o PID,UID,NAME,RSS", 20_000)
        if (result.code != 0 && result.out.isBlank()) {
            return RunningSnapshot(error = "Nu pot citi lista proceselor: ${result.err}")
        }

        val byUid = HashMap<Int, UidProcesses>()
        result.out.lineSequence().drop(1).forEach { line ->
            val match = psRegex.find(line) ?: return@forEach
            val pid = match.groupValues[1].toIntOrNull() ?: return@forEach
            val uid = match.groupValues[2].toIntOrNull() ?: return@forEach
            val rss = match.groupValues[4].toLongOrNull() ?: 0L

            val bucket = byUid.getOrPut(uid) { UidProcesses() }
            bucket.pids += pid
            bucket.rssKb += rss
        }
        return RunningSnapshot(byUid)
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

    private class UidProcesses {
        val pids = mutableListOf<Int>()
        var rssKb = 0L
    }

    private class RunningSnapshot(
        val byUid: Map<Int, UidProcesses> = emptyMap(),
        val error: String? = null,
    ) {
        companion object {
            val EMPTY = RunningSnapshot()
        }
    }
}