package ro.davinci.checker.shell

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Alege și ține backend-ul de shell folosit de aplicație.
 *
 * Ordine la pornire:
 *  1. Shizuku, dacă permisiunea e deja acordată (cel mai rapid, fără port);
 *  2. ADB pe hostul/portul salvat (implicit 127.0.0.1:5040 — serverul din Termux);
 *  3. nimic — aplicația arată lista, dar fără stare „rulează” și fără acțiuni.
 */
class ShellController(private val context: Context) {

    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data class Connected(val kind: ShellBackend.Kind, val label: String) : State
        data class Failed(val message: String) : State
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var state: State = State.Disconnected
        private set

    var backend: ShellBackend? = null
        private set

    var lastError: String? = null
        private set

    var adbHost: String
        get() = prefs.getString(KEY_HOST, AdbShell.DEFAULT_HOST) ?: AdbShell.DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var adbPort: Int
        get() = prefs.getInt(KEY_PORT, AdbShell.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    fun isReady(): Boolean = backend != null

    /** Execută o comandă; aruncă dacă nu există backend (UI verifică înainte). */
    fun exec(command: String, timeoutMs: Long = 60_000): ShellResult {
        val active = backend ?: return ShellResult("", "Niciun backend conectat", -1)
        return active.exec(command, timeoutMs)
    }

    fun maybeExec(command: String, timeoutMs: Long = 60_000): ShellResult? {
        val active = backend ?: return null
        return active.exec(command, timeoutMs)
    }

    suspend fun autoConnect(): Boolean = withContext(Dispatchers.IO) {
        // ADB primul: dacă serverul rulează deja (Termux), e gata imediat și nu
        // cere permisiuni. Shizuku e a doua variantă, pentru telefoane fără adb.
        if (tryAdb(adbHost, adbPort)) return@withContext true
        if (tryShizuku()) return@withContext true
        state = State.Disconnected
        false
    }

    suspend fun tryShizuku(): Boolean = withContext(Dispatchers.IO) {
        val shell = ShizukuShell(context.applicationContext)
        if (!shell.permissionGranted()) return@withContext false

        state = State.Connecting
        if (shell.probe()) {
            replace(shell)
            true
        } else {
            lastError = "Shizuku nu răspunde comenzilor shell."
            false
        }
    }

    suspend fun tryAdb(host: String = adbHost, port: Int = adbPort): Boolean =
        withContext(Dispatchers.IO) {
            state = State.Connecting
            adbHost = host
            adbPort = port

            val shell = AdbShell(host, port)
            val result = shell.exec("echo checker-ok; id -u", 8_000)
            if (result.code == 0 && result.out.contains("checker-ok")) {
                replace(shell)
                true
            } else {
                lastError = result.err.ifBlank {
                    "Serverul adb nu răspunde pe $host:$port."
                }
                state = State.Failed(lastError!!)
                false
            }
        }

    fun useLocalFallback() {
        replace(LocalShell())
    }

    fun disconnect() {
        backend?.close()
        backend = null
        state = State.Disconnected
    }

    private fun replace(shell: ShellBackend) {
        backend?.close()
        backend = shell
        lastError = null
        state = State.Connected(shell.kind, shell.label)
    }

    private companion object {
        const val PREFS = "checker_shell"
        const val KEY_HOST = "adb_host"
        const val KEY_PORT = "adb_port"
    }
}