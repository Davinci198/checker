package ro.davinci.checker.data

import android.os.SystemClock
import ro.davinci.checker.shell.ShellBackend

/** Procesele care aparțin unui UID, agregate dintr-o singură citire de `ps`. */
data class UidProcesses(
    val pids: List<Int> = emptyList(),
    val rssKb: Long = 0,
    /** CPU instantaneu, în procente dintr-un nucleu (poate depăși 100 la mai multe fire). */
    val cpuPercent: Float = 0f,
    /** Totalul firelor de execuție ale proceselor aplicației. */
    val threadCount: Int = 0,
) {
    val processCount: Int get() = pids.size
}

/** O eșantionare completă: starea sistemului + procesele pe UID. */
data class LiveSample(
    val stats: SystemStats = SystemStats.EMPTY,
    val processes: Map<Int, UidProcesses> = emptyMap(),
    val error: String? = null,
)

/**
 * Citește starea sistemului și procesele **într-un singur apel shell**.
 *
 * De ce un singur apel: fiecare comandă prin adb sau Shizuku e un round-trip.
 * Aici se citesc, în aceeași comandă:
 *
 *  - `/proc/stat`      -> CPU total (diferență între două eșantioane)
 *  - `/proc/meminfo`   -> RAM și swap
 *  - `/proc/loadavg`   -> load average
 *  - `ps -A`           -> PID, UID, RSS
 *  - `/proc/<pid>/stat`-> timpul CPU cumulat și numărul de fire (awk, o singură trecere)
 *  - zonele termice și clusterele cpufreq (dacă nucleul le expune)
 *  - GPU (kgsl), doar pe nucleele care permit citirea din uid `shell`
 *
 * CPU-ul per proces e calculat din diferența de jiffies între eșantioane, nu din
 * media pe durata vieții procesului: altfel o aplicație care a consumat mult la
 * pornire ar rămâne veșnic „fierbinte", iar una care tocmai a început să ardă
 * ar apărea rece.
 */
class LiveProbe {

    private var previousAt = 0L
    private var previousJiffies: Map<Int, ProcStat> = emptyMap()
    private var previousCpu: CpuTimes? = null

    fun sample(shell: ShellBackend): LiveSample {
        val startedAt = SystemClock.elapsedRealtime()
        val result = shell.exec(COMMAND, 15_000)
        val sampleMillis = SystemClock.elapsedRealtime() - startedAt
        if (result.out.isBlank()) {
            return LiveSample(
                error = "Nu pot citi starea sistemului: " +
                    result.err.ifBlank { "răspuns gol" }.trim(),
            )
        }

        val blocks = split(result.out)
        val statBlock = blocks[STAT] ?: ""
        val memBlock = blocks[MEM] ?: ""
        val loadBlock = blocks[LOAD] ?: ""
        val procBlock = blocks[PROC] ?: ""
        val jiffiesBlock = blocks[JIFF] ?: ""

        val (cpuPercent, cpuTimes) = parseCpu(statBlock, previousCpu)
        previousCpu = cpuTimes

        val (hottestC, hottestZone) = parseThermal(blocks[THERM] ?: "")

        val ramTotal = memKb(memBlock, "MemTotal")
        val ramFree = memKb(memBlock, "MemAvailable").takeIf { it > 0 }
            ?: memKb(memBlock, "MemFree")
        val swapTotal = memKb(memBlock, "SwapTotal")
        val swapFree = memKb(memBlock, "SwapFree")

        val stats = SystemStats(
            cpuPercent = cpuPercent,
            ramUsedKb = (ramTotal - ramFree).coerceAtLeast(0),
            ramTotalKb = ramTotal,
            swapUsedKb = (swapTotal - swapFree).coerceAtLeast(0),
            swapTotalKb = swapTotal,
            load1 = loadBlock.trim().substringBefore(' ').toFloatOrNull() ?: 0f,
            cores = statBlock.lineSequence().count { CORE_REGEX.matches(it.substringBefore(' ')) },
            hottestC = hottestC,
            hottestZone = hottestZone,
            clusters = parseClusters(blocks[FREQ] ?: ""),
            gpu = parseGpu(blocks[GPU] ?: ""),
            battery = parseBattery(blocks[BATT] ?: ""),
            sampleMillis = sampleMillis,
        )

        val buckets = HashMap<Int, Bucket>()
        val uidOfPid = HashMap<Int, Int>()
        procBlock.lineSequence().forEach { line ->
            val match = PS_REGEX.find(line) ?: return@forEach
            val pid = match.groupValues[1].toIntOrNull() ?: return@forEach
            val uid = match.groupValues[2].toIntOrNull() ?: return@forEach
            val rss = match.groupValues[3].toLongOrNull() ?: 0L

            val bucket = buckets.getOrPut(uid) { Bucket() }
            bucket.pids += pid
            bucket.rssKb += rss
            uidOfPid[pid] = uid
        }

        val jiffies = parseJiffies(jiffiesBlock)
        val now = SystemClock.elapsedRealtime()
        val elapsedSeconds = (now - previousAt) / 1000.0

        // Firele de execuție nu depind de eșantionul anterior: se pot lua din
        // prima citire, ca aplicația să aibă numărul corect imediat.
        jiffies.forEach { (pid, stat) ->
            val uid = uidOfPid[pid] ?: return@forEach
            buckets.getOrPut(uid) { Bucket() }.threads += stat.threads
        }

        if (previousAt > 0 && elapsedSeconds >= MIN_WINDOW_SECONDS && jiffies.isNotEmpty()) {
            jiffies.forEach { (pid, stat) ->
                val before = previousJiffies[pid] ?: return@forEach
                val used = stat.jiffies - before.jiffies
                if (used <= 0) return@forEach
                val uid = uidOfPid[pid] ?: return@forEach
                buckets.getOrPut(uid) { Bucket() }.cpuJiffies += used
            }
        }

        previousJiffies = jiffies
        previousAt = now

        val processes = buckets.mapValues { (_, bucket) ->
            UidProcesses(
                pids = bucket.pids,
                rssKb = bucket.rssKb,
                cpuPercent = if (bucket.cpuJiffies <= 0) {
                    0f
                } else {
                    (bucket.cpuJiffies / HZ / elapsedSeconds * 100.0).toFloat()
                },
                threadCount = bucket.threads,
            )
        }

        return LiveSample(stats = stats, processes = processes)
    }

    // ------------------------------------------------------------- parsare

    /** Împarte ieșirea în blocuri, după marcajele scrise de comandă. */
    private fun split(output: String): Map<String, String> {
        val blocks = HashMap<String, String>()
        var key: String? = null
        val buffer = StringBuilder()

        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed in MARKERS) {
                key?.let { blocks[it] = buffer.toString() }
                key = trimmed
                buffer.clear()
            } else if (key != null) {
                buffer.append(line).append('\n')
            }
        }
        key?.let { blocks[it] = buffer.toString() }
        return blocks
    }

    private fun parseCpu(block: String, previous: CpuTimes?): Pair<Float, CpuTimes?> {
        val line = block.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return 0f to previous
        val values = line.trim().split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return 0f to previous

        // user, nice, system, idle, iowait, irq, softirq, steal
        val idle = values[3] + (values.getOrNull(4) ?: 0)
        val total = values.take(8).sum()
        val current = CpuTimes(total = total, idle = idle)

        if (previous == null) return 0f to current
        val deltaTotal = total - previous.total
        val deltaIdle = idle - previous.idle
        if (deltaTotal <= 0) return 0f to current

        val percent = ((deltaTotal - deltaIdle) * 100.0 / deltaTotal)
            .toFloat()
            .coerceIn(0f, 100f)
        return percent to current
    }

    /**
     * Zonele termice. Comanda le citește pe toate cu un singur `grep -H`, deci
     * primele linii arată ca `/sys/class/thermal/thermal_zone0/temp:32100`.
     * Unele drivere raportează miligrade, altele zecimi de grad sau grade
     * întregi, deci valoarea se normalizează după mărime.
     */
    private fun parseThermal(block: String): Pair<Float, String> {
        val names = HashMap<String, String>(8)
        val temps = HashMap<String, Float>(8)

        block.lineSequence().forEach { line ->
            val match = THERMAL_REGEX.find(line) ?: return@forEach
            val zone = match.groupValues[1]
            val value = match.groupValues[3].trim()
            when (match.groupValues[2]) {
                "type" -> names[zone] = value
                "temp" -> {
                    val raw = value.toFloatOrNull() ?: return@forEach
                    temps[zone] = when {
                        raw >= 1_000f -> raw / 1_000f
                        raw >= 200f -> raw / 10f
                        else -> raw
                    }
                }
            }
        }

        var hottest = 0f
        var zoneName = ""
        temps.forEach { (zone, celsius) ->
            if (celsius in 1f..150f && celsius > hottest) {
                hottest = celsius
                zoneName = names[zone].orEmpty().ifBlank { "zona $zone" }
            }
        }
        return hottest to zoneName
    }

    /**
     * Clusterele cpufreq, tot dintr-un `grep -H`:
     * `/sys/.../cpufreq/policy0/scaling_cur_freq:1804800`.
     */
    private fun parseClusters(block: String): List<CpuCluster> {
        val current = HashMap<String, Int>(4)
        val maximum = HashMap<String, Int>(4)
        val cpus = HashMap<String, String>(4)

        block.lineSequence().forEach { line ->
            val match = FREQ_REGEX.find(line) ?: return@forEach
            val policy = match.groupValues[1]
            val value = match.groupValues[3].trim()
            when (match.groupValues[2]) {
                "scaling_cur_freq" -> current[policy] = (value.toIntOrNull() ?: 0) / 1_000
                "cpuinfo_max_freq" -> maximum[policy] = (value.toIntOrNull() ?: 0) / 1_000
                "related_cpus" -> cpus[policy] = value.split(WHITESPACE).joinToString(",")
            }
        }

        return current.keys.sorted().map { policy ->
            CpuCluster(
                policy = policy,
                cpus = cpus[policy].orEmpty(),
                currentMhz = current[policy] ?: 0,
                maxMhz = maximum[policy] ?: 0,
            )
        }
    }

    /** GPU: `gpubusy=<ocupat> <total>` sau `pct=<procent>` plus frecvențele. */
    private fun parseGpu(block: String): GpuStats? {
        var percent: Float? = null
        var curMhz = 0
        var maxMhz = 0
        block.lineSequence().forEach { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim()
            when (key) {
                "gpubusy" -> {
                    val parts = value.split(WHITESPACE).mapNotNull { it.toLongOrNull() }
                    if (parts.size == 2 && parts[1] > 0) {
                        percent = (parts[0] * 100f / parts[1]).coerceIn(0f, 100f)
                    }
                }
                "pct" -> {
                    val raw = value.toFloatOrNull()
                    if (raw != null) percent = raw.coerceIn(0f, 100f)
                }
                "cur" -> curMhz = (value.toIntOrNull() ?: 0) / 1_000_000
                "max" -> maxMhz = (value.toIntOrNull() ?: 0) / 1_000_000
            }
        }
        if (percent == null && curMhz == 0) return null
        return GpuStats(percent = percent ?: 0f, currentMhz = curMhz, maxMhz = maxMhz)
    }

    /**
     * Bateria. Liniile arată ca
     * `/sys/class/power_supply/battery/capacity:21`.
     *
     * Unele telefoane (Motorola) au două noduri — `battery` și `mmi_battery` —
     * care nu cad mereu de acord; se dă prioritate lui `battery`, pentru că de
     * el ascultă și sistemul când afișează procentul.
     */
    private fun parseBattery(block: String): BatteryStats? {
        if (block.isBlank()) return null
        val byNode = HashMap<String, MutableMap<String, String>>(2)

        block.lineSequence().forEach { line ->
            val match = BATTERY_REGEX.find(line) ?: return@forEach
            val node = match.groupValues[1]
            val key = match.groupValues[2]
            val value = match.groupValues[3].trim()
            byNode.getOrPut(node) { HashMap(12) }[key] = value
        }

        val node = byNode["battery"] ?: byNode["mmi_battery"] ?: return null
        val percent = node["capacity"]?.toIntOrNull() ?: return null
        if (percent !in 0..100) return null

        // Kernelul raportează capacitatea în µAh și curentul în µA, dar unele
        // drivere dau direct mAh/mA; pragul desparte cele două convenții.
        fun milli(raw: String?): Int {
            val value = raw?.toIntOrNull() ?: return 0
            return if (kotlin.math.abs(value) > 100_000) value / 1_000 else value
        }

        val temperature = node["temp"]?.toFloatOrNull()?.let {
            if (it >= 200f) it / 10f else it
        } ?: 0f

        return BatteryStats(
            percent = percent,
            status = when (node["status"]?.lowercase()) {
                "charging" -> BatteryStats.Status.CHARGING
                "discharging" -> BatteryStats.Status.DISCHARGING
                "full" -> BatteryStats.Status.FULL
                "not charging" -> BatteryStats.Status.NOT_CHARGING
                else -> BatteryStats.Status.UNKNOWN
            },
            designMah = milli(node["charge_full_design"]),
            fullMah = milli(node["charge_full"]),
            currentMa = milli(node["current_now"]),
            tempC = temperature,
            cycles = node["cycle_count"]?.toIntOrNull() ?: 0,
            health = node["health"].orEmpty(),
        )
    }

    private fun memKb(block: String, key: String): Long =
        block.lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()
            ?: 0L

    private fun parseJiffies(block: String): Map<Int, ProcStat> {
        if (block.isBlank()) return emptyMap()
        val map = HashMap<Int, ProcStat>(512)
        block.lineSequence().forEach { line ->
            val match = JIFFIES_REGEX.find(line) ?: return@forEach
            val pid = match.groupValues[1].toIntOrNull() ?: return@forEach
            val jiffies = match.groupValues[2].toLongOrNull() ?: return@forEach
            val threads = match.groupValues[3].toIntOrNull() ?: 0
            map[pid] = ProcStat(jiffies, threads)
        }
        return map
    }

    private data class ProcStat(val jiffies: Long, val threads: Int)

    private data class CpuTimes(val total: Long, val idle: Long)

    private class Bucket {
        val pids = ArrayList<Int>(2)
        var rssKb = 0L
        var cpuJiffies = 0L
        var threads = 0
    }

    companion object {
        /** USER_HZ pe Android: 100 de tick-uri pe secundă. */
        private const val HZ = 100.0

        /** Sub această fereastră, diferența de jiffies e prea zgomotoasă. */
        private const val MIN_WINDOW_SECONDS = 0.3

        private const val STAT = "@@CHECKER_STAT"
        private const val MEM = "@@CHECKER_MEM"
        private const val LOAD = "@@CHECKER_LOAD"
        private const val PROC = "@@CHECKER_PROC"
        private const val JIFF = "@@CHECKER_JIFF"
        private const val THERM = "@@CHECKER_THERM"
        private const val FREQ = "@@CHECKER_FREQ"
        private const val GPU = "@@CHECKER_GPU"
        private const val BATT = "@@CHECKER_BATT"

        private val MARKERS = setOf(STAT, MEM, LOAD, PROC, JIFF, THERM, FREQ, GPU, BATT)

        private val PS_REGEX = Regex("""^\s*(\d+)\s+(\d+)\s+(\d+)\s*$""")

        /** `pid <jiffies> <fire>`; vezi awk-ul din [COMMAND]. */
        private val JIFFIES_REGEX = Regex("""^\s*(\d+)\s+(\d+)\s+(\d+)\s*$""")
        private val CORE_REGEX = Regex("""^cpu\d+$""")
        private val WHITESPACE = Regex("""\s+""")

        /** `.../thermal_zone12/temp:38000` -> zona 12, tipul `temp`, valoarea. */
        private val THERMAL_REGEX = Regex("""thermal_zone(\d+)/(temp|type):(.*)$""")

        /** `.../cpufreq/policy0/scaling_cur_freq:1804800`. */
        private val FREQ_REGEX = Regex("""cpufreq/(policy\d+)/(\w+):(.*)$""")

        /** `.../power_supply/battery/capacity:21`. */
        private val BATTERY_REGEX = Regex("""power_supply/(\w+)/(\w+):(.*)$""")

        /** Câmpurile citite din baterie; ordinea nu contează pentru parsare. */
        private val BATTERY_FIELDS = listOf(
            "status", "capacity", "charge_full_design", "charge_full",
            "current_now", "temp", "cycle_count", "health",
        )

        private val COMMAND = buildString {
            append("echo $STAT; cat /proc/stat; ")
            append("echo $MEM; cat /proc/meminfo; ")
            append("echo $LOAD; cat /proc/loadavg; ")
            append("echo $PROC; ps -A -o PID,UID,RSS; ")
            append("echo $JIFF; ")
            // `sub(/^.*\)/,"")` taie „pid (comm)" până la ultima paranteză, ca
            // numele cu spații („Web Content") să nu decaleze câmpurile.
            // b[12]+b[13] = utime+stime, b[18] = numărul de fire.
            append("awk '{split(FILENAME,a,\"/\"); l=\$0; sub(/^.*\\)/,\"\",l); ")
            append("n=split(l,b,\" \"); print a[3], b[12]+b[13], b[18]}' /proc/[0-9]*/stat; ")
            append("echo $THERM; ")
            append("grep -H . /sys/class/thermal/thermal_zone*/temp ")
            append("/sys/class/thermal/thermal_zone*/type 2>/dev/null; ")
            append("echo $FREQ; ")
            append("grep -H . /sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq ")
            append("/sys/devices/system/cpu/cpufreq/policy*/cpuinfo_max_freq ")
            append("/sys/devices/system/cpu/cpufreq/policy*/related_cpus 2>/dev/null; ")
            // GPU: pe multe nuclee /sys/class/kgsl e permis doar root-ului, deci
            // fiecare citire e condiționată; dacă nimic nu răspunde, blocul e gol
            // și GPU-ul pur și simplu nu apare în interfață.
            append("echo $GPU; ")
            append("for pair in gpubusy:gpubusy gpu_busy_percentage:pct; do ")
            append("v=\$(cat /sys/class/kgsl/kgsl-3d0/\${pair%%:*} 2>/dev/null); ")
            append("[ -n \"\$v\" ] && echo \"\${pair##*:}=\$v\"; done; ")
            append("v=\$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null); ")
            append("[ -n \"\$v\" ] && echo \"cur=\$v\"; ")
            append("v=\$(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null); ")
            append("[ -n \"\$v\" ] && echo \"max=\$v\"; ")
            // Bateria: două `grep` (nu 18 `cat`), pe ambele noduri posibile.
            append("echo $BATT; ")
            listOf("battery", "mmi_battery").forEach { node ->
                append("grep -H . ")
                BATTERY_FIELDS.forEach { field ->
                    append("/sys/class/power_supply/$node/$field ")
                }
                append("2>/dev/null; ")
            }
            append("true")
        }
    }
}
