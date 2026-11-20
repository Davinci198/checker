package ro.davinci.checker.data

/**
 * Starea sistemului la un moment dat: CPU total, RAM, swap și load average.
 *
 * Valorile vin din `/proc/stat`, `/proc/meminfo` și `/proc/loadavg`, citite
 * printr-un singur apel shell (vezi [LiveProbe]).
 */
data class SystemStats(
    val cpuPercent: Float = 0f,
    val ramUsedKb: Long = 0,
    val ramTotalKb: Long = 0,
    val swapUsedKb: Long = 0,
    val swapTotalKb: Long = 0,
    val load1: Float = 0f,
    val cores: Int = 0,
) {
    val ramPercent: Float get() = percent(ramUsedKb, ramTotalKb)
    val swapPercent: Float get() = percent(swapUsedKb, swapTotalKb)
    val hasSwap: Boolean get() = swapTotalKb > 0

    val cpuLabel: String get() = if (cpuPercent <= 0f) "—" else "%.0f%%".format(cpuPercent)

    val ramLabel: String get() = if (ramTotalKb <= 0) {
        "—"
    } else {
        "${AppEntry.formatKb(ramUsedKb)} / ${AppEntry.formatKb(ramTotalKb)}"
    }

    val swapLabel: String get() = "${AppEntry.formatKb(swapUsedKb)} / ${AppEntry.formatKb(swapTotalKb)}"

    val loadLabel: String get() = "%.2f".format(load1)

    companion object {
        val EMPTY = SystemStats()

        private fun percent(used: Long, total: Long): Float =
            if (total <= 0) 0f else (used * 100f / total).coerceIn(0f, 100f)
    }
}
