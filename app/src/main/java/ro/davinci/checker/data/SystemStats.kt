package ro.davinci.checker.data

import java.util.Locale

/**
 * Starea sistemului la un moment dat: CPU total, RAM, swap și load average.
 *
 * Valorile vin din `/proc/stat`, `/proc/meminfo` și `/proc/loadavg`, citite
 * printr-un singur apel shell (vezi [LiveProbe]).
 */
/** Un cluster de nuclee: frecvența curentă și plafonul lui. */
data class CpuCluster(
    /** Numele politicii cpufreq, ex. `policy0`. */
    val policy: String,
    /** Nucleele din cluster, ex. `0,1,2,3`. */
    val cpus: String,
    val currentMhz: Int,
    val maxMhz: Int,
)

/** Sarcina GPU-ului, dacă nucleul permite citirea din uid `shell`. */
data class GpuStats(
    val percent: Float = 0f,
    val currentMhz: Int = 0,
    val maxMhz: Int = 0,
) {
    val percentLabel: String get() = "%.0f%%".format(Locale.ROOT, percent)
}

data class SystemStats(
    val cpuPercent: Float = 0f,
    val ramUsedKb: Long = 0,
    val ramTotalKb: Long = 0,
    val swapUsedKb: Long = 0,
    val swapTotalKb: Long = 0,
    val load1: Float = 0f,
    val cores: Int = 0,
    /** Cea mai caldă zonă termică, în °C. */
    val hottestC: Float = 0f,
    /** Numele zonei celei mai calde, ex. `cpu-0-2`. */
    val hottestZone: String = "",
    val clusters: List<CpuCluster> = emptyList(),
    /** `null` când nucleul nu lasă uid-ul `shell` să citească starea GPU-ului. */
    val gpu: GpuStats? = null,
    /** Cât a durat citirea întregului eșantion, în milisecunde. */
    val sampleMillis: Long = 0,
) {
    val ramPercent: Float get() = percent(ramUsedKb, ramTotalKb)
    val swapPercent: Float get() = percent(swapUsedKb, swapTotalKb)
    val hasSwap: Boolean get() = swapTotalKb > 0

    val cpuLabel: String get() = if (cpuPercent <= 0f) "—" else "%.0f%%".format(cpuPercent)

    val tempLabel: String get() =
        if (hottestC <= 0f) "—" else "%.1f °C".format(Locale.ROOT, hottestC).replace('.', ',')

    /** Plafonul cel mai mare dintre clustere, în GHz. */
    val maxFreqLabel: String
        get() {
            val max = clusters.maxOfOrNull { it.maxMhz } ?: 0
            return if (max <= 0) "—" else "%.2f GHz".format(Locale.ROOT, max / 1000f).replace('.', ',')
        }

    /** Sub 50 °C nu merită semnalat; peste 60 °C telefonul chiar frânează. */
    val heatLevel: Int
        get() = when {
            hottestC >= 60f -> 2
            hottestC >= 50f -> 1
            else -> 0
        }

    val ramLabel: String get() = if (ramTotalKb <= 0) {
        "—"
    } else {
        "${AppEntry.formatKb(ramUsedKb)} / ${AppEntry.formatKb(ramTotalKb)}"
    }

    val swapLabel: String get() = "${AppEntry.formatKb(swapUsedKb)} / ${AppEntry.formatKb(swapTotalKb)}"

    val loadLabel: String get() = "%.2f".format(load1)

    /**
     * Sarcina GPU, gata de afișat: „42% · 680 MHz”. `null` înseamnă că nucleul
     * nu expune citirea (SELinux), caz în care rândul lipsește cu totul.
     */
    val gpuLabel: String?
        get() {
            val stats = gpu ?: return null
            val freq = if (stats.currentMhz > 0) " · ${stats.currentMhz} MHz" else ""
            return stats.percentLabel + freq
        }

    companion object {
        val EMPTY = SystemStats()

        private fun percent(used: Long, total: Long): Float =
            if (total <= 0) 0f else (used * 100f / total).coerceIn(0f, 100f)
    }
}
