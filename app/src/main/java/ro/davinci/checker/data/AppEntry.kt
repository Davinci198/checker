package ro.davinci.checker.data

/** O aplicație instalată, așa cum o vede interfața. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val isFrozen: Boolean,
    val versionName: String,
    val apkPaths: List<String>,
    val updatedAt: Long,
    val pids: List<Int> = emptyList(),
    val rssKb: Long = 0,
    val installedAt: Long = 0,
    val targetSdk: Int = 0,
    val minSdk: Int = 0,
    val installer: String? = null,
    val permissionCount: Int = 0,
    val dangerousPermissions: Int = 0,
    val hasLaunchIntent: Boolean = false,
    val isPersistent: Boolean = false,
    val hasForegroundService: Boolean = false,
    /** UID-ul e împărțit cu alte pachete (ex. uid 1000 = system_server). */
    val sharedUid: Boolean = false,
    /** CPU instantaneu al proceselor pachetului, în procente dintr-un nucleu. */
    val cpuPercent: Float = 0f,
    /** Câte fire de execuție are pachetul acum, în toate procesele lui. */
    val threadCount: Int = 0,
    val safety: StopSafety = StopSafety.SAFE,
    val safetyReason: String = "",
) {
    val running: Boolean get() = pids.isNotEmpty()

    /** Memoria care poate fi pusă pe seama acestui pachet (nu e partajată). */
    val ownRssKb: Long get() = if (sharedUid) 0 else rssKb

    /**
     * Textul de memorie, calculat o dată la scanare.
     * `String.format` e scump, iar rândul din listă îl citea la fiecare compunere.
     */
    val memoryLabel: String = when {
        sharedUid && pids.isNotEmpty() -> "partajat"
        rssKb > 0 -> formatKb(rssKb)
        else -> ""
    }

    /** Primul PID, gata de afișat. */
    val pidLabel: String = pids.firstOrNull()?.let { "PID $it" }.orEmpty()

    /**
     * Procentul de CPU, gata de afișat.
     * Sub 0,1% nu are sens să scriem nimic: ar umple rândul cu zgomot.
     */
    val cpuLabel: String = when {
        cpuPercent >= 10f -> "%.0f%%".format(cpuPercent)
        cpuPercent >= 1f -> "%.1f%%".format(cpuPercent)
        cpuPercent >= 0.1f -> "<1%"
        else -> ""
    }

    /** Poate fi oprită fără să afecteze telefonul, cât timp rulează. */
    val safeToStop: Boolean get() = safety == StopSafety.SAFE

    /** „12 fire” — numărul de fire din toate procesele aplicației. */
    val threadsLabel: String = when {
        threadCount <= 0 -> ""
        threadCount == 1 -> "1 fir"
        threadCount < 20 -> "$threadCount fire"
        else -> "$threadCount de fire"
    }

    companion object {
        fun formatKb(kb: Long): String = when {
            kb >= 1024 * 1024 -> "%.1f GB".format(kb / 1024.0 / 1024.0)
            kb >= 1024 -> "%.0f MB".format(kb / 1024.0)
            else -> "$kb KB"
        }
    }
}

/** Rezultatul unei scanări complete. */
data class ScanResult(
    val apps: List<AppEntry>,
    val foregroundPackage: String? = null,
    val shellKind: String? = null,
    val warning: String? = null,
    val stats: SystemStats = SystemStats.EMPTY,
)

fun formatKb(kb: Long): String = AppEntry.formatKb(kb)
