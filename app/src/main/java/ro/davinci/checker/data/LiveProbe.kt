package ro.davinci.checker.data

import android.os.SystemClock
import ro.davinci.checker.shell.ShellBackend

/** Procesele care aparțin unui UID, agregate dintr-o singură citire de `ps`. */
data class UidProcesses(
    val pids: List<Int> = emptyList(),
    val rssKb: Long = 0,
    /** CPU instantaneu, în procente dintr-un nucleu (poate depăși 100 la mai multe fire). */
    val cpuPercent: Float = 0f,
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
 *  - `/proc/<pid>/stat`-> timpul CPU cumulat pe proces (awk, o singură trecere)
 *
 * CPU-ul per proces e calculat din diferența de jiffies între eșantioane, nu din
 * media pe durata vieții procesului: altfel o aplicație care a consumat mult la
 * pornire ar rămâne veșnic „fierbinte", iar una care tocmai a început să ardă
 * ar apărea rece.
 */
class LiveProbe {

    private var previousAt = 0L
    private var previousJiffies: Map<Int, Long> = emptyMap()
    private var previousCpu: CpuTimes? = null

    fun sample(shell: ShellBackend): LiveSample {
        val result = shell.exec(COMMAND, 15_000)
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

        if (previousAt > 0 && elapsedSeconds >= MIN_WINDOW_SECONDS && jiffies.isNotEmpty()) {
            jiffies.forEach { (pid, total) ->
                val before = previousJiffies[pid] ?: return@forEach
                val used = total - before
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

    private fun memKb(block: String, key: String): Long =
        block.lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()
            ?: 0L

    private fun parseJiffies(block: String): Map<Int, Long> {
        if (block.isBlank()) return emptyMap()
        val map = HashMap<Int, Long>(512)
        block.lineSequence().forEach { line ->
            val match = JIFFIES_REGEX.find(line) ?: return@forEach
            val pid = match.groupValues[1].toIntOrNull() ?: return@forEach
            val jiffies = match.groupValues[2].toLongOrNull() ?: return@forEach
            map[pid] = jiffies
        }
        return map
    }

    private data class CpuTimes(val total: Long, val idle: Long)

    private class Bucket {
        val pids = ArrayList<Int>(2)
        var rssKb = 0L
        var cpuJiffies = 0L
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

        private val MARKERS = setOf(STAT, MEM, LOAD, PROC, JIFF)

        private val PS_REGEX = Regex("""^\s*(\d+)\s+(\d+)\s+(\d+)\s*$""")
        private val JIFFIES_REGEX = Regex("""^\s*(\d+)\s+(\d+)\s*$""")
        private val CORE_REGEX = Regex("""^cpu\d+$""")
        private val WHITESPACE = Regex("""\s+""")

        private val COMMAND = buildString {
            append("echo $STAT; cat /proc/stat; ")
            append("echo $MEM; cat /proc/meminfo; ")
            append("echo $LOAD; cat /proc/loadavg; ")
            append("echo $PROC; ps -A -o PID,UID,RSS; ")
            append("echo $JIFF; ")
            append("awk '{split(FILENAME,a,\"/\"); print a[3], \$14+\$15}' /proc/[0-9]*/stat")
        }
    }
}
