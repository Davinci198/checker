package ro.davinci.checker.shell

import java.util.concurrent.TimeUnit

/**
 * Backend local, fără privilegii: rulează `sh` ca uid-ul aplicației.
 *
 * Folosit doar ca să putem citi informații proprii (ex. `pm path` pentru
 * pachetul curent) și ca să avem un mod de lucru degradat când nici adb,
 * nici Shizuku nu sunt disponibile. Comenzile `pm disable-user`,
 * `pm uninstall` etc. vor eșua aici — și e normal.
 */
class LocalShell : ShellBackend {

    override val kind: ShellBackend.Kind = ShellBackend.Kind.LOCAL
    override val label: String = "local (fără privilegii)"

    override fun probe(timeoutMs: Long): Boolean = true

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        return try {
            val process = ProcessBuilder("sh", "-c", Framing.wrap(command)).start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return ShellResult(stdout, "Timeout ($timeoutMs ms)", -1)
            }
            val parsed = Framing.parse(stdout)
            if (parsed.code < 0 && stderr.isNotBlank()) {
                ShellResult(parsed.out, stderr.trim(), -1)
            } else {
                parsed
            }
        } catch (t: Throwable) {
            ShellResult("", t.message ?: t.javaClass.simpleName, -1)
        }
    }
}