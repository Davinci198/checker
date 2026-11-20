package ro.davinci.checker

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import ro.davinci.checker.data.ApkBackup
import ro.davinci.checker.data.AppActions
import ro.davinci.checker.data.AppEntry
import ro.davinci.checker.data.AppLists
import ro.davinci.checker.data.AppScanner
import ro.davinci.checker.data.BloatInfo
import ro.davinci.checker.data.BloatVerdict
import ro.davinci.checker.data.DebloatDb
import ro.davinci.checker.data.StopSafety
import ro.davinci.checker.data.SystemStats
import ro.davinci.checker.shell.ShellController
import ro.davinci.checker.watch.CpuWatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppFilter(val label: String) {
    ALL("Toate"),
    RUNNING("Rulează"),
    BACKGROUND("Fundal"),
    HOT("Consum CPU"),
    SAFE_STOP("Oprire sigură"),
    USER("Utilizator"),
    SYSTEM("Sistem"),
    FROZEN("Înghețate"),
    WHITELIST("Listă albă"),
    BLACKLIST("Listă neagră"),
    DEBLOAT("Debloat"),
}

/** Ordinea listei. `AUTO` alege memoria pentru tab-urile de procese. */
enum class SortMode(val label: String) {
    AUTO("Automat"),
    RAM("Memorie"),
    CPU("CPU"),
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
    val whiteList: Set<String> = emptySet(),
    val blackList: Set<String> = emptySet(),
    /** Baza de debloat: pachet -> informații. Se încarcă o singură dată. */
    val debloat: Map<String, BloatInfo> = emptyMap(),
    val selection: Set<String> = emptySet(),
    val stats: SystemStats = SystemStats.EMPTY,
    val cpuHistory: List<Float> = emptyList(),
    val ramHistory: List<Float> = emptyList(),
    val liveEnabled: Boolean = true,
    /** Paznicul CPU din fundal (notificări pentru consum mare). */
    val cpuWatch: Boolean = false,
    val loading: Boolean = false,
    val busyLabel: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val adbHost: String = "127.0.0.1",
    val adbPort: String = "5040",
    val warning: String? = null,
) {
    val connected: Boolean get() = connection is ShellController.State.Connected

    /** Selecția e activă cât timp există cel puțin un pachet ales. */
    val selecting: Boolean get() = selection.isNotEmpty()

    /**
     * Verdictul care se afișează: lista albă are întâietate față de orice
     * calcul, fiindcă e o decizie explicită a utilizatorului.
     */
    fun safetyOf(app: AppEntry): StopSafety =
        if (app.packageName in whiteList) StopSafety.NEVER else app.safety

    fun safetyReasonOf(app: AppEntry): String =
        if (app.packageName in whiteList) {
            "Este în lista albă: aplicația nu se oprește nici individual, nici în masă."
        } else {
            app.safetyReason
        }

    /** Poate fi oprită fără să afecteze telefonul (ține cont și de lista albă). */
    fun safeToStop(app: AppEntry): Boolean =
        safetyOf(app) == StopSafety.SAFE

    /** Ce ordine se aplică efectiv pentru filtrul curent. */
    fun effectiveSort(): SortMode = when (sortMode) {
        SortMode.AUTO -> when (filter) {
            AppFilter.HOT -> SortMode.CPU
            AppFilter.RUNNING, AppFilter.BACKGROUND, AppFilter.SAFE_STOP -> SortMode.RAM
            else -> SortMode.NAME
        }
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
                AppFilter.HOT -> app.running && app.cpuPercent >= HOT_THRESHOLD
                AppFilter.SAFE_STOP -> app.running && safeToStop(app)
                AppFilter.USER -> !app.isSystem
                AppFilter.SYSTEM -> app.isSystem
                AppFilter.FROZEN -> app.isFrozen
                AppFilter.WHITELIST -> app.packageName in whiteList
                AppFilter.BLACKLIST -> app.packageName in blackList
                AppFilter.DEBLOAT -> inDebloat(app)
            }
            matchesQuery && matchesFilter
        }

        // Tab-ul de debloat se ordonează mereu după verdict: ce e sigur de
        // eliminat sus, ce e periculos jos. Sortarea aleasă de utilizator nu se
        // aplică aici, fiindcă ordinea e chiar sensul listei.
        if (filter == AppFilter.DEBLOAT) {
            return filtered.sortedWith(
                compareBy<AppEntry> { debloat[it.packageName]?.verdict?.order ?: Int.MAX_VALUE }
                    .thenBy { it.label.lowercase() },
            )
        }

        return when (effectiveSort()) {
            SortMode.RAM -> filtered.sortedWith(
                compareByDescending<AppEntry> { it.ownRssKb }
                    .thenBy { it.label.lowercase() },
            )
            SortMode.CPU -> filtered.sortedWith(
                compareByDescending<AppEntry> { it.cpuPercent }
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
        AppFilter.HOT -> apps.count { it.running && it.cpuPercent >= HOT_THRESHOLD }
        AppFilter.SAFE_STOP -> apps.count { it.running && safeToStop(it) }
        AppFilter.USER -> apps.count { !it.isSystem }
        AppFilter.SYSTEM -> apps.count { it.isSystem }
        AppFilter.FROZEN -> apps.count { it.isFrozen }
        AppFilter.WHITELIST -> apps.count { it.packageName in whiteList }
        AppFilter.BLACKLIST -> apps.count { it.packageName in blackList }
        AppFilter.DEBLOAT -> apps.count { inDebloat(it) }
    }

    /** Pachetul are un verdict util în baza de debloat. */
    fun inDebloat(app: AppEntry): Boolean =
        debloat[app.packageName]?.verdict?.let { it != BloatVerdict.UNKNOWN } == true

    fun bloatOf(app: AppEntry): BloatInfo? =
        debloat[app.packageName]?.takeIf { it.verdict != BloatVerdict.UNKNOWN }

    /** Memoria ocupată de procesele aplicațiilor, fără UID-uri partajate. */
    val runningMemoryKb: Long
        get() = apps.asSequence()
            .filter { it.running && !it.sharedUid }
            .sumOf { it.rssKb }

    companion object {
        /** De la acest procent o aplicație e considerată consumator de CPU. */
        const val HOT_THRESHOLD = 1f
    }
}

class CheckerViewModel(application: Application) : AndroidViewModel(application) {

    private val shellController = ShellController(application)
    private val scanner = AppScanner(application)
    private val backupStore = ApkBackup(application)
    private val lists = AppLists(application)
    private val debloatDb = DebloatDb(application)
    private val prefs = application.getSharedPreferences("checker_prefs", Context.MODE_PRIVATE)

    private var liveJob: Job? = null
    private var screenVisible = false
    private var tickCount = 0

    private val _ui = MutableStateFlow(
        UiState(
            adbHost = shellController.adbHost,
            adbPort = shellController.adbPort.toString(),
            whiteList = lists.white(),
            blackList = lists.black(),
            liveEnabled = prefs.getBoolean(KEY_LIVE, true),
            cpuWatch = CpuWatch.isEnabled(application),
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
        loadDebloat()
        refreshBackups()
        autoConnect()
    }

    /** Baza de debloat se citește o singură dată, pe un fir de fundal. */
    private fun loadDebloat() {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) { debloatDb.load() }
            if (map.isNotEmpty()) _ui.value = _ui.value.copy(debloat = map)
        }
    }

    override fun onCleared() {
        liveJob?.cancel()
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
        syncLive()
    }

    // --------------------------------------------------------------- scanare

    fun scan() {
        if (_ui.value.loading) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, busyLabel = null)

            val backend = shellController.backend
            val result = withContext(Dispatchers.IO) { scanner.scan(backend) }

            val previous = _ui.value
            val hasStats = result.stats.ramTotalKb > 0
            _ui.value = previous.copy(
                apps = result.apps,
                foregroundPackage = result.foregroundPackage,
                warning = result.warning,
                loading = false,
                stats = result.stats.takeIf { hasStats } ?: previous.stats,
                cpuHistory = if (hasStats) {
                    (previous.cpuHistory + result.stats.cpuPercent).takeLast(HISTORY_SIZE)
                } else {
                    previous.cpuHistory
                },
                ramHistory = if (hasStats) {
                    (previous.ramHistory + result.stats.ramPercent).takeLast(HISTORY_SIZE)
                } else {
                    previous.ramHistory
                },
            )
            syncLive()
        }
    }

    // ------------------------------------------------------------ live stats

    /** Ecranul e vizibil: pornește eșantionarea dacă utilizatorul nu a oprit-o. */
    fun onScreenVisible() {
        screenVisible = true
        syncLive()
    }

    fun onScreenHidden() {
        screenVisible = false
        syncLive()
    }

    /** Pornește sau oprește graficele live (alegerea se ține minte). */
    fun setLiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE, enabled).apply()
        _ui.value = _ui.value.copy(liveEnabled = enabled)
        syncLive()
    }

    /**
     * Pornește sau oprește paznicul CPU. Serviciul se pornește din interfață,
     * deci aplicația e în prim-plan și Android permite un serviciu în prim-plan.
     */
    fun setCpuWatch(enabled: Boolean) {
        CpuWatch.setEnabled(getApplication<Application>(), enabled)
        _ui.value = _ui.value.copy(cpuWatch = enabled)
        if (enabled) {
            showMessage("Paznicul CPU e pornit: primești un anunț când o aplicație arde CPU în fundal.")
        }
    }

    private fun syncLive() {
        val state = _ui.value
        val shouldRun = screenVisible && state.liveEnabled && state.connected
        if (!shouldRun) {
            liveJob?.cancel()
            liveJob = null
            return
        }
        if (liveJob?.isActive == true) return

        liveJob = viewModelScope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    /**
     * Un tick: o singură comandă shell pentru CPU/RAM/procese, apoi se
     * actualizează doar câmpurile care se schimbă. Lista de pachete nu se
     * rescană — altfel ar însemna 551 de intrări la fiecare 2 secunde.
     */
    private suspend fun tick() {
        val backend = shellController.backend ?: return
        val sample = withContext(Dispatchers.IO) { scanner.sample(backend) }
        if (sample.error != null) return

        tickCount++
        val foreground = if (tickCount % FOREGROUND_EVERY == 0) {
            withContext(Dispatchers.IO) { scanner.foreground(backend) }
        } else {
            null
        }

        val state = _ui.value
        val apps = state.apps.map { app ->
            val proc = sample.processes[app.uid]
            if (proc == null) {
                app.copy(pids = emptyList(), rssKb = 0, cpuPercent = 0f, threadCount = 0)
            } else {
                app.copy(
                    pids = proc.pids,
                    rssKb = proc.rssKb,
                    cpuPercent = proc.cpuPercent,
                    threadCount = proc.threadCount,
                )
            }
        }

        _ui.value = state.copy(
            apps = apps,
            stats = sample.stats,
            cpuHistory = (state.cpuHistory + sample.stats.cpuPercent).takeLast(HISTORY_SIZE),
            ramHistory = (state.ramHistory + sample.stats.ramPercent).takeLast(HISTORY_SIZE),
            foregroundPackage = foreground ?: state.foregroundPackage,
        )
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
        if (app.packageName in _ui.value.whiteList) {
            showMessage(
                "${app.label} este în lista albă, deci nu a fost oprită. " +
                    "Scoate-o din listă dacă vrei să o poți opri.",
                isError = true,
            )
            return
        }
        runAction("Oprește ${app.label}…") { shell ->
            AppActions(shell).forceStop(app.packageName)
        }
    }

    /**
     * Oprește dintr-un singur gest toate aplicațiile selectate.
     *
     * Sunt sărite cele din **lista albă** și cele care nu rulează; restul sunt
     * oprite pe rând, iar la final se raportează câte au reușit și câte nu.
     */
    fun stopSelected() {
        val state = _ui.value
        if (state.selection.isEmpty()) {
            showMessage("Nu ai selectat nicio aplicație.")
            return
        }

        val backend = shellController.backend
        if (backend == null) {
            showMessage("Ai nevoie de adb sau Shizuku pentru această acțiune.", isError = true)
            return
        }

        val selected = state.apps.filter { it.packageName in state.selection }
        val protectedOnes = selected.filter { it.packageName in state.whiteList }
        val targets = selected.filter {
            it.packageName !in state.whiteList && it.running
        }
        val alreadyStopped = selected.count {
            it.packageName !in state.whiteList && !it.running
        }

        if (targets.isEmpty()) {
            showMessage(
                when {
                    protectedOnes.isNotEmpty() ->
                        "Toate aplicațiile selectate sunt în lista albă."
                    alreadyStopped > 0 ->
                        "Aplicațiile selectate nu rulează."
                    else -> "Nu am ce opri."
                },
                isError = true,
            )
            return
        }

        viewModelScope.launch {
            _ui.value = _ui.value.copy(busyLabel = "Opresc ${targets.size} aplicații…")
            val actions = AppActions(backend)

            var stopped = 0
            var failed = 0
            var stoppedKb = 0L

            withContext(Dispatchers.IO) {
                targets.forEach { app ->
                    val result = actions.forceStop(app.packageName)
                    if (result.ok) {
                        stopped++
                        if (!app.sharedUid) stoppedKb += app.rssKb
                    } else {
                        failed++
                    }
                }
            }

            _ui.value = _ui.value.copy(busyLabel = null, selection = emptySet())

            val freedMemory = if (stoppedKb > 0) {
                " Am eliberat ~${AppEntry.formatKb(stoppedKb)}."
            } else {
                ""
            }
            val skipped = when {
                protectedOnes.isNotEmpty() -> " Sărite din lista albă: ${protectedOnes.size}."
                else -> ""
            }

            if (failed == 0) {
                showMessage("Am oprit $stopped aplicații.$freedMemory$skipped")
            } else {
                showMessage(
                    "Am oprit $stopped aplicații, $failed au eșuat.$freedMemory$skipped",
                    isError = true,
                )
            }

            scan()
        }
    }

    // ------------------------------------------------------------- selecție

    /** Intră în modul de selecție cu pachetul dat (apăsare lungă pe rând). */
    fun toggleSelection(packageName: String) {
        val current = _ui.value.selection
        _ui.value = _ui.value.copy(
            selection = if (packageName in current) current - packageName else current + packageName,
        )
    }

    /** Selectează tot ce se vede acum (după cautare și filtru). */
    fun selectAllVisible() {
        val visible = _ui.value.visible().map { it.packageName }.toSet()
        _ui.value = _ui.value.copy(selection = visible)
    }

    fun clearSelection() {
        _ui.value = _ui.value.copy(selection = emptySet())
    }

    // --------------------------------------------------------------- liste

    /** Adaugă sau scoate din lista albă / neagră. */
    fun toggleList(kind: AppLists.Kind, packageName: String) {
        lists.toggle(kind, packageName)
        applyLists()
        val name = listName(kind)
        val inside = packageName in lists.of(kind)
        showMessage(
            if (inside) "$packageName a fost adăugat în $name."
            else "$packageName a fost scos din $name.",
        )
    }

    /** Mută selecția curentă într-o listă și golește selecția. */
    fun addSelectedToList(kind: AppLists.Kind) {
        val selected = _ui.value.selection
        if (selected.isEmpty()) {
            showMessage("Nu ai selectat nicio aplicație.")
            return
        }
        lists.add(kind, selected)
        applyLists()
        _ui.value = _ui.value.copy(selection = emptySet())
        showMessage(
            "Am adăugat ${selected.size} aplicații în ${listName(kind)}.",
        )
    }

    fun clearList(kind: AppLists.Kind) {
        lists.clear(kind)
        applyLists()
        showMessage("Am golit ${listName(kind)}.")
    }

    private fun applyLists() {
        _ui.value = _ui.value.copy(
            whiteList = lists.white(),
            blackList = lists.black(),
        )
    }

    private fun listName(kind: AppLists.Kind): String = when (kind) {
        AppLists.Kind.WHITE -> "lista albă"
        AppLists.Kind.BLACK -> "lista neagră"
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

        /** Cadența eșantionării cât timp ecranul e vizibil. */
        private const val TICK_MS = 2_000L

        /** Câte eșantioane ține graficul (60 × 2 s = 2 minute). */
        private const val HISTORY_SIZE = 60

        /** La fiecare atâtea tick-uri se reîmprospătează și pachetul din prim-plan. */
        private const val FOREGROUND_EVERY = 8

        private const val KEY_LIVE = "live_stats"

        fun formatDate(millis: Long): String =
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro", "RO")).format(Date(millis))
    }
}