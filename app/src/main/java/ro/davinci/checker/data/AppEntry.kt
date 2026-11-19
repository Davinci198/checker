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
) {
    val running: Boolean get() = pids.isNotEmpty()

    /** Cheltuie memorie (RSS) agregată pe toate procesele pachetului. */
    val memoryLabel: String get() = if (rssKb > 0) formatKb(rssKb) else ""

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
)

fun formatKb(kb: Long): String = AppEntry.formatKb(kb)