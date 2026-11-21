package ro.davinci.checker.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Momentele în care bateria și-a schimbat starea, citite din istoricul
 * sistemului (`dumpsys batterystats --history`).
 *
 * De ce nu doar observația proprie: aplicația poate fi închisă exact când
 * utilizatorul bagă sau scoate încărcătorul, iar atunci ora și procentul
 * reținute ar fi cele de la prima deschidere, nu cele reale. Istoricul
 * sistemului ține însă fiecare tranziție, cu ora și nivelul de atunci — deci
 * „descărcarea a început la 22:08, de la 100%" iese exact.
 *
 * Limite: istoricul e un tampon circular (pe acest telefon cam 7 zile), iar pe
 * unele telefoane e dezactivat. Când lipsește, rămâne observația proprie.
 */
data class BatteryEvents(
    /** Când a început ultima încărcare. */
    val chargeAt: Long = 0L,
    /** Procentul de la care a pornit ultima încărcare. */
    val chargePercent: Int = -1,
    /** Când a ajuns bateria la 100% în ultima încărcare. */
    val fullAt: Long = 0L,
    /** Când a început descărcarea curentă (sau ultima). */
    val dischargeAt: Long = 0L,
    /** Procentul de la care a pornit descărcarea: 100, 99, 98… */
    val dischargePercent: Int = -1,
)

object BatteryHistory {

    /**
     * `tail` e suficient: ne interesează doar ultimele tranziții. Comanda durează
     * ~1,3 s, de aceea se rulează rar (vezi `BatteryLog.seedFrom`).
     */
    const val COMMAND =
        "dumpsys batterystats --history 2>/dev/null " +
            "| grep -E ' status=(charging|discharging|full)' | tail -n 60"

    /** `  11-19 22:08:53.212 100 status=discharging …`. Anul lipsește. */
    private val LINE = Regex(
        """^\s*(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{1,3}) (\d{1,3}) status=(\w+)""",
    )

    fun parse(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): BatteryEvents {
        var events = BatteryEvents()
        var status = ""          // starea ultimei linii
        var chargeLevel = -1     // procentul ultimei linii de încărcare
        var dischargeLevel = -1  // procentul ultimei linii de descărcare

        text.lineSequence().forEach { line ->
            val match = LINE.find(line) ?: return@forEach
            val level = match.groupValues[7].toIntOrNull() ?: return@forEach
            if (level !in 0..100) return@forEach
            val at = moment(match, now, zone) ?: return@forEach
            val kind = match.groupValues[8].lowercase()

            when (kind) {
                "charging", "full" -> {
                    // Încărcare nouă: fie după o descărcare, fie după o scădere
                    // de nivel — semn că între timp bateria s-a descărcat fără
                    // ca istoricul să fi prins tranziția.
                    val wasCharging = status == "charging" || status == "full"
                    val dropped = chargeLevel >= 0 && level < chargeLevel - 1
                    if (!wasCharging || dropped) {
                        events = events.copy(chargeAt = at, chargePercent = level, fullAt = 0L)
                    }
                    chargeLevel = level
                }
                "discharging" -> {
                    // Descărcare nouă: după o încărcare sau după o creștere de
                    // nivel (bateria a fost încărcată, dar tranziția lipsise).
                    val rose = dischargeLevel >= 0 && level > dischargeLevel + 1
                    if (status != "discharging" || rose) {
                        events = events.copy(dischargeAt = at, dischargePercent = level)
                    }
                    dischargeLevel = level
                }
                else -> return@forEach
            }

            if (kind == "full" && events.fullAt <= 0L) {
                events = events.copy(fullAt = at)
            }
            status = kind
        }

        return events
    }

    /**
     * Reconstruiește momentul din lună/zi/oră. Istoricul nu scrie anul, deci se
     * pornește de la anul curent; dacă rezultatul iese în viitor, evenimentul e
     * din anul trecut (istoricul poate traversa 31 decembrie).
     */
    private fun moment(match: MatchResult, now: Long, zone: ZoneId): Long? {
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val hour = match.groupValues[3].toIntOrNull() ?: return null
        val minute = match.groupValues[4].toIntOrNull() ?: return null
        val second = match.groupValues[5].toIntOrNull() ?: return null
        val millis = match.groupValues[6].padEnd(3, '0').toIntOrNull() ?: return null

        val year = Instant.ofEpochMilli(now).atZone(zone).year

        fun build(y: Int): Long? = runCatching {
            LocalDateTime.of(y, month, day, hour, minute, second, millis * 1_000_000)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()

        val current = build(year) ?: return null
        // O toleranță de o oră acoperă diferențele de fus orar și secundele.
        return if (current > now + 3_600_000L) build(year - 1) ?: current else current
    }
}