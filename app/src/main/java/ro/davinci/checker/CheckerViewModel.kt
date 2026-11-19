package ro.davinci.checker

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import ro.davinci.checker.data.ApkBackup
import ro.davinci.checker.data.AppActions
import ro.davinci.checker.data.AppEntry
import ro.davinci.checker.data.AppScanner
import ro.davinci.checker.shell.ShellController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppFilter(val label: String) {
    ALL("Toate"),
    RUNNING("Rulează"),
    BACKGROUND("Fundal"),
    SAFE_STOP("Oprire sigură"),
    USER("Utilizator"),
    SYSTEM("Sistem"),
    FROZEN("Înghețate"),
}

/** Ordinea listei. `AUTO` alege memoria pentru tab-urile de procese. */
enum class SortMode(val label: String) {
    AUTO("Automat"),
    RAM("Memorie"),
    NAME("Nume"),
}

data class UiState(
    val connection: ShellController.State = ShellController.State.Disconnected,
    val connectionError: String? = null,
    val apps: List<AppEntry> = emptyList(),
    val foregroundPackage: String? = null,
    val backups: List<ApkBackup.Entry> = emptyList(),
    val query: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val sortMode: SortMode = SortMode.AUTO,
    val loading: Boolean = false,
    val busyLabel: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val adbHost: String = "127.0.0.1",
    val adbPort: String = "5040",
    val warning: String? = null,
) {
    val connected: Boolean get() = connection is ShellController.State.Connected

    /** Ce ordine se aplică efectiv pentru filtrul curent. */
    fun effectiveSort(): SortMode = when (sortMode) {
        SortMode.AUTO ->
            if (filter == AppFilter.RUNNING || filter == AppFilter.BACKGROUND ||
                filter == AppFilter.SAFE_STOP
            ) SortMode.RAM else SortMode.NAME
        else -> sortMode
    }

    fun visible(): List<AppEntry> {
        val term = query.trim().lowercase()
        val filtered = apps.filter { app ->
            val matchesQuery = term.isEmpty() ||
                app.label.lowercase().contains(term) ||
                app.packageName.lowercase().contains(term)

            val matchesFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.RUNNING -> app.running
                AppFilter.BACKGROUND -> app.running && app.packageName != foregroundPackage
                AppFilter.SAFE_STOP -> app.running && app.safeToStop
                AppFilter.USER -> !app.isSystem
                AppFilter.SYSTEM -> app.isSystem
                AppFilter.FROZEN -> app.isFrozen
            }
            matchesQuery && matchesFilter
        }

        return when (effectiveSort()) {
            SortMode.RAM -> filtered.sortedWith(
                compareByDescending<AppEntry> { it.ownRssKb }
                    .thenBy { it.label.lowercase() },
            )
            else -> filtered.sortedWith(
                compareBy<AppEntry> { it.isFrozen }.thenBy { it.label.lowercase() },
            )
        }
    }

    fun count(filter: AppFilter): Int = when (filter) {
        AppFilter.ALL -> apps.size
        AppFilter.RUNNING -> apps.count { it.running }
        AppFilter.BACKGROUND ->
            apps.count { it.running && it.packageName != foregroundPackage }
        AppFilter.SAFE_STOP -> apps.count { it.running && it.safeToStop }
        AppFilter.USER -> apps.count { !it.isSystem }
        AppFilter.SYSTEM -> apps.count { it.isSystem }
        AppFilter.FROZEN -> apps.count { it.isFrozen }
    }

    /** Memoria ocupată de procesele aplicațiilor, fără UID-uri partajate. */
    val runningMemoryKb: Long
        get() = apps.asSequence()
            .filter { it.running && !it.sharedUid }
            .sumOf { it.rssKb }
}

class CheckerViewModel(application: Application) : AndroidViewModel(application) {

    private val shellController = ShellController(application)
    private val scanner = AppScanner(application)
    private val backupStore = ApkBackup(application)

    private val _ui = MutableStateFlow(
        UiState(
            adbHost = shellController.adbHost,
            adbPort = shellController.adbPort.toString(),
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                connectShizuku()
            } else {
                showMessage("Accesul Shizuku a fost refuzat.", isError = true)
            }
        }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            connectShizuku(silent = true)
        }
    }

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener(binderListener)
        }
        refreshBackups()
        autoConnect()
    }

    override fun onCleared() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderListener)
        }
        super.onCleared()
    }

    // ------------------------------------------------------------- conexiune

    private fun autoConnect() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busyLabel = "Caută o conexiune privilegiată…")
            val connected = shellController.autoConnect()
            applyConnectionState()
            _ui.value = _ui.value.copy(busyLabel = null)
            if (connected) {
                scan()
            } else {
                _ui.value = _ui.value.copy(
                    warning = shellController.lastError
                        ?: "Fără adb și fără Shizuku. Lista se vede, dar starea „rulează” lipsește.",
                )
            }
        }
    }

    fun connectAdb(host: String, portText: String) {
        val port = portText.trim().toIntOrNull()
        if (host.isBlank() || port == null) {
            showMessage("Host sau port invalid.", isError = true)
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                busyLabel = "Conectez la adb $host:$port…",
                adbHost = host,
                adbPort = port.toString(),
            )
            val ok = shellController.tryAdb(host, port)
            applyConnectionState()
            _ui.value = _ui.value.copy(busyLabel = null)
            if (ok) {
                showMessage("Conectat prin adb la $host:$port.")
                scan()
            } else {
                showMessage(
                    shellController.lastError ?: "Conexiunea adb a eșuat.",
                    isError = true,
                )
            }
        }
    }

    fun connectShizuku(silent: Boolean = false) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busyLabel = "Conectez prin Shizuku…")
            val ok = shellController.tryShizuku()
            applyConnectionState()
            _ui.value = _ui.value.copy(busyLabel = null)
            when {
                ok -> {
                    if (!silent) showMessage("Conectat prin Shizuku.")
                    scan()
                }
                silent -> Unit
                else -> showMessage(
                    shellController.lastError ?: "Shizuku nu a răspuns.",
                    isError = true,
                )
            }
        }
    }

    fun requestShizukuPermission() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            showMessage(
                "Shizuku nu rulează. Deschide aplicația Shizuku și pornește serviciul, " +
                    "sau folosește conexiunea adb.",
                isError = true,
            )
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            connectShizuku()
        } else {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                .onFailure { showMessage("Cererea Shizuku a eșuat: ${it.message}", isError = true) }
        }
    }

    private fun applyConnectionState() {
        _ui.value = _ui.value.copy(
            connection = shellController.state,
            connectionError = shellController.lastError,
        )
    }

    // --------------------------------------------------------------- scanare

    fun scan() {
        if (_ui.value.loading) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, busyLabel = null)

            val backend = shellController.backend
            val result = withContext(Dispatchers.IO) { scanner.scan(backend) }

            _ui.value = _ui.value.copy(
                apps = result.apps,
                foregroundPackage = result.foregroundPackage,
                warning = result.warning,
                loading = false,
            )
        }
    }

    // -------------------------------------------------------------- acțiuni

    fun freeze(app: AppEntry) {
        runAction("Îngheț ${app.label}…") { shell ->
            AppActions(shell).freeze(app.packageName)
        }
    }

    fun unfreeze(app: AppEntry) {
        runAction("Dezgheț ${app.label}…") { shell ->
            AppActions(shell).unfreeze(app.packageName)
        }
    }

    fun forceStop(app: AppEntry) {
        runAction("Oprește ${app.label}…") { shell ->
            AppActions(shell).forceStop(app.packageName)
        }
    }

    fun backupOnly(app: AppEntry) {
        viewModelScope.launch {
            val backend = shellController.backend
            if (backend == null) {
                showMessage("Ai nevoie de adb sau Shizuku pentru copierea APK.", isError = true)
                return@launch
            }
            _ui.value = _ui.value.copy(busyLabel = "Copiez APK-ul pentru ${app.label}…")
            val result = withContext(Dispatchers.IO) {
                backupStore.backup(backend, app.packageName, app.label)
            }
            _ui.value = _ui.value.copy(busyLabel = null)
            if (result.error != null) {
                showMessage(result.error!!, isError = true)
            } else {
                val entry = result.entry!!
                refreshBackups()
                showMessage(
                    "APK salvat (${AppEntry.formatKb(entry.totalBytes / 1024)}) în ${entry.dir}",
                )
            }
        }
    }

    /**
     * Dezinstalează aplicația. Dacă [withBackup] e true, întâi copiază APK-urile;
     * dacă backup-ul eșuează, dezinstalarea NU se face.
     */
    fun uninstall(app: AppEntry, withBackup: Boolean) {
        viewModelScope.launch {
            val backend = shellController.backend
            if (backend == null) {
                showMessage("Ai nevoie de adb sau Shizuku pentru dezinstalare.", isError = true)
                return@launch
            }

            var backupNote = ""
            if (withBackup) {
                _ui.value = _ui.value.copy(busyLabel = "Copiez APK-ul înainte de dezinstalare…")
                val backup = withContext(Dispatchers.IO) {
                    backupStore.backup(backend, app.packageName, app.label)
                }
                if (backup.error != null) {
                    _ui.value = _ui.value.copy(busyLabel = null)
                    showMessage(
                        "Dezinstalare anulată: copia APK a eșuat (${backup.error}).",
                        isError = true,
                    )
                    return@launch
                }
                backupNote = " Copie APK: ${backup.entry!!.dir}"
                refreshBackups()
            }

            _ui.value = _ui.value.copy(busyLabel = "Dezinstalez ${app.label}…")
            val result = withContext(Dispatchers.IO) {
                backupStore.uninstall(backend, app.packageName, app.isSystem)
            }
            _ui.value = _ui.value.copy(busyLabel = null)

            if (result.out.contains("Success")) {
                showMessage("${app.label} a fost dezinstalată.$backupNote")
                scan()
            } else {
                showMessage(
                    "Dezinstalarea a eșuat: ${result.text().ifBlank { "răspuns gol de la pm" }}",
                    isError = true,
                )
            }
        }
    }

    fun restoreBackup(entry: ApkBackup.Entry) {
        viewModelScope.launch {
            val backend = shellController.backend
            if (backend == null) {
                showMessage("Ai nevoie de adb sau Shizuku pentru reinstalare.", isError = true)
                return@launch
            }
            _ui.value = _ui.value.copy(busyLabel = "Reinstalez ${entry.label}…")
            val result = withContext(Dispatchers.IO) { backupStore.restore(backend, entry) }
            _ui.value = _ui.value.copy(busyLabel = null)

            if (result.out.contains("Success")) {
                showMessage("${entry.label} a fost reinstalată.")
                scan()
            } else {
                showMessage(
                    "Reinstalarea a eșuat: ${result.text().ifBlank { "răspuns gol de la pm" }}",
                    isError = true,
                )
            }
        }
    }

    fun deleteBackup(entry: ApkBackup.Entry) {
        viewModelScope.launch {
            val backend = shellController.backend
            if (backend == null) {
                showMessage("Ai nevoie de adb sau Shizuku pentru ștergere.", isError = true)
                return@launch
            }
            withContext(Dispatchers.IO) { backupStore.delete(backend, entry) }
            refreshBackups()
            showMessage("Backup-ul pentru ${entry.label} a fost șters.")
        }
    }

    fun refreshBackups() {
        _ui.value = _ui.value.copy(backups = backupStore.backups())
    }

    fun launchIntent(packageName: String): Intent? =
        getApplication<Application>().packageManager.getLaunchIntentForPackage(packageName)

    // ------------------------------------------------------------- filtrare

    fun setQuery(query: String) {
        _ui.value = _ui.value.copy(query = query)
    }

    fun setFilter(filter: AppFilter) {
        _ui.value = _ui.value.copy(filter = filter)
    }

    fun setSort(mode: SortMode) {
        _ui.value = _ui.value.copy(sortMode = mode)
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null, messageIsError = false)
    }

    private fun runAction(busy: String, action: (ro.davinci.checker.shell.ShellBackend) -> AppActions.Result) {
        viewModelScope.launch {
            val backend = shellController.backend
            if (backend == null) {
                showMessage("Ai nevoie de adb sau Shizuku pentru această acțiune.", isError = true)
                return@launch
            }
            _ui.value = _ui.value.copy(busyLabel = busy)
            val result = withContext(Dispatchers.IO) { action(backend) }
            _ui.value = _ui.value.copy(busyLabel = null)

            if (result.ok) {
                showMessage(result.message)
                scan()
            } else {
                showMessage(result.message, isError = true)
            }
        }
    }

    private fun showMessage(text: String, isError: Boolean = false) {
        _ui.value = _ui.value.copy(message = text, messageIsError = isError)
    }

    companion object {
        private const val REQUEST_CODE = 4711

        fun formatDate(millis: Long): String =
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro", "RO")).format(Date(millis))
    }
}