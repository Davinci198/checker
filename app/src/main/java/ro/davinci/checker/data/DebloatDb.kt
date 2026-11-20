package ro.davinci.checker.data

import android.content.Context
import org.json.JSONArray

/**
 * Verdictul din baza de date de debloat.
 *
 * Ordinea contează: [order] e folosită la sortare, ca aplicațiile sigure de
 * eliminat să apară primele și cele critice la coadă.
 */
enum class BloatVerdict(
    val label: String,
    val short: String,
    val order: Int,
) {
    RECOMMENDED("Recomandat de eliminat", "recomandat", 0),
    ADVANCED("Pentru utilizatori avansați", "avansat", 1),
    EXPERT("Doar pentru experți", "experți", 2),
    UNSAFE("Nu elimina", "risc", 3),
    UNKNOWN("Fără verdict", "", 4),
    ;

    /** Merită confirmare suplimentară înainte de dezinstalare. */
    val needsWarning: Boolean get() = this == EXPERT || this == UNSAFE

    companion object {
        fun of(value: String): BloatVerdict =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Ce știm despre un pachet preinstalat, din lista UAD. */
data class BloatInfo(
    val packageName: String,
    /** Sursa pachetului: Oem, Aosp, Google, Carrier, Misc, Pending. */
    val origin: String,
    val verdict: BloatVerdict,
    val description: String,
) {
    /** Eticheta pentru rândul din listă; goală pentru pachetele fără verdict. */
    val shortLabel: String get() = verdict.short

    val originLabel: String
        get() = when (origin) {
            "Oem" -> "producător"
            "Aosp" -> "Android"
            "Google" -> "Google"
            "Carrier" -> "operator"
            "Pending" -> "neverificat"
            "Misc" -> "divers"
            else -> origin.ifBlank { "necunoscut" }
        }
}

/**
 * Baza de date de debloat, citită o singură dată din `assets/debloat.json`.
 *
 * Sursa datelor: Universal Android Debloater (lista de pachete și verdictele),
 * preluată prin proiectul TaskManager. Vezi `docs/COMPARATIE-TaskManager.md`.
 * Fișierul e doar text; parsarea se face pe un fir de fundal, în [load].
 */
class DebloatDb(private val context: Context) {

    /** @return hartă pachet -> informații; goală dacă datele nu pot fi citite. */
    fun load(): Map<String, BloatInfo> = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        val map = HashMap<String, BloatInfo>(array.length() * 2)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("p").takeIf { it.isNotBlank() } ?: continue
            map[packageName] = BloatInfo(
                packageName = packageName,
                origin = item.optString("l"),
                verdict = BloatVerdict.of(item.optString("r")),
                description = item.optString("d").trim(),
            )
        }
        map
    }.getOrDefault(emptyMap())

    private companion object {
        const val ASSET = "debloat.json"
    }
}
