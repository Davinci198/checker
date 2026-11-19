package ro.davinci.checker.data

import android.content.Context

/**
 * Cele două liste ale utilizatorului, ținute în `SharedPreferences` ca seturi de
 * pachete.
 *
 *  - **Lista albă** = aplicații protejate. Nu se opresc nici din acțiunea
 *    individuală, nici din oprirea în masă; sunt sărite cu un mesaj clar.
 *  - **Lista neagră** = aplicații pe care le oprești mereu. Filtrul „Listă
 *    neagră” le arată într-un singur loc, ca să le poți opri dintr-un gest.
 *
 * Un pachet stă într-o singură listă: adăugarea într-una îl scoate din cealaltă,
 * ca să nu existe stări contradictorii.
 */
class AppLists(context: Context) {

    enum class Kind(val label: String) {
        WHITE("lista albă"),
        BLACK("lista neagră"),
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun of(kind: Kind): Set<String> = when (kind) {
        Kind.WHITE -> prefs.getStringSet(KEY_WHITE, emptySet())
        Kind.BLACK -> prefs.getStringSet(KEY_BLACK, emptySet())
    }.orEmpty().toSet()

    fun white(): Set<String> = of(Kind.WHITE)

    fun black(): Set<String> = of(Kind.BLACK)

    /**
     * Adaugă pachetele în [kind] și le scoate din cealaltă listă.
     * Întoarce lista rezultată pentru [kind].
     */
    fun add(kind: Kind, packages: Collection<String>): Set<String> {
        val target = of(kind).toMutableSet()
        target.addAll(packages)
        val other = of(other(kind)).toMutableSet()
        other.removeAll(packages)

        write(kind, target)
        write(other(kind), other)
        return target.toSet()
    }

    /** Scoate pachetele din [kind]. */
    fun remove(kind: Kind, packages: Collection<String>): Set<String> {
        val target = of(kind).toMutableSet()
        target.removeAll(packages)
        write(kind, target)
        return target.toSet()
    }

    /** Adaugă dacă lipsește, scoate dacă există. Întoarce `true` dacă a rămas în listă. */
    fun toggle(kind: Kind, packageName: String): Boolean {
        return if (packageName in of(kind)) {
            remove(kind, listOf(packageName))
            false
        } else {
            add(kind, listOf(packageName))
            true
        }
    }

    /** Golește o listă. */
    fun clear(kind: Kind) {
        write(kind, emptySet())
    }

    private fun other(kind: Kind): Kind = when (kind) {
        Kind.WHITE -> Kind.BLACK
        Kind.BLACK -> Kind.WHITE
    }

    private fun write(kind: Kind, values: Set<String>) {
        // Copie: SharedPreferences nu acceptă să modifici setul primit de la el.
        prefs.edit()
            .putStringSet(if (kind == Kind.WHITE) KEY_WHITE else KEY_BLACK, values.toSet())
            .apply()
    }

    companion object {
        private const val PREFS = "checker_lists"
        private const val KEY_WHITE = "white_list"
        private const val KEY_BLACK = "black_list"
    }
}
