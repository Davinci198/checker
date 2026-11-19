package ro.davinci.checker.shell

/**
 * Rezultatul unei comenzi rulate pe dispozitiv prin orice backend.
 *
 * Toate backends trimit comanda printr-un shell POSIX, deci `$?` e mereu
 * disponibil; [Framing] lipește un marker la final ca să putem separa
 * codul de ieșire de text.
 */
data class ShellResult(
    val out: String,
    val err: String = "",
    val code: Int = -1,
) {
    val ok: Boolean get() = code == 0

    /** true dacă textul conține un marcaj tipic de succes al `pm`. */
    val pmSuccess: Boolean
        get() = out.contains("Success") || out.contains("new state")

    fun text(): String = listOf(out, err).filter { it.isNotBlank() }.joinToString("\n").trim()
}

/** Un backend care poate executa comenzi shell pe telefon. */
interface ShellBackend {
    val kind: Kind
    val label: String

    /** Verifică rapid dacă backend-ul răspunde și are privilegii de shell. */
    fun probe(timeoutMs: Long = 8_000): Boolean

    fun exec(command: String, timeoutMs: Long = 60_000): ShellResult

    fun close() {}

    enum class Kind { ADB, SHIZUKU, LOCAL }
}

/**
 * Împachetarea comenzilor: unește stderr în stdout și adaugă codul de ieșire
 * pe ultima linie, sub un marker greu de confundat cu ieșirea reală.
 */
internal object Framing {
    const val MARK = "__CHECKER_RC="

    fun wrap(command: String): String = "{ $command ; } 2>&1; echo \"$MARK\$?\""

    fun parse(raw: String): ShellResult {
        val trimmed = raw.trimEnd('\n', '\r')
        val idx = trimmed.lastIndexOf(MARK)
        if (idx < 0) return ShellResult(trimmed, "", -1)
        val code = trimmed.substring(idx + MARK.length).trim().toIntOrNull() ?: -1
        return ShellResult(trimmed.substring(0, idx).trimEnd('\n', '\r'), "", code)
    }
}

/** Escapează un argument pentru shell-ul POSIX (single-quote). */
fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"