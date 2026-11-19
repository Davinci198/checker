package ro.davinci.checker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davinci.checker.AppFilter
import ro.davinci.checker.CheckerViewModel
import ro.davinci.checker.SortMode
import ro.davinci.checker.UiState
import ro.davinci.checker.data.AppEntry
import ro.davinci.checker.data.IconCache
import ro.davinci.checker.data.StopSafety

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckerApp(vm: CheckerViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showBackups by rememberSaveable { mutableStateOf(false) }
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUninstall by rememberSaveable { mutableStateOf<String?>(null) }
    var backupBeforeUninstall by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(ui.message) {
        val message = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearMessage()
    }

    if (showBackups) {
        BackupsScreen(
            backups = ui.backups,
            onBack = { showBackups = false },
            onRestore = vm::restoreBackup,
            onDelete = vm::deleteBackup,
            onRefresh = vm::refreshBackups,
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Column {
                        Text("Checker", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = connectionLine(ui),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.scan() }, enabled = !ui.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescanează")
                    }
                    IconButton(onClick = { vm.refreshBackups(); showBackups = true }) {
                        Icon(Icons.Default.Folder, contentDescription = "Backup-uri APK")
                    }
                    IconButton(onClick = { showConnection = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Conexiune")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            if (!ui.connected) {
                ConnectionCard(ui) { showConnection = true }
            }

            if (ui.busyLabel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(ui.busyLabel!!, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (ui.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Caută după nume sau pachet") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Golește")
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(AppFilter.entries.toList()) { filter ->
                    FilterChip(
                        selected = ui.filter == filter,
                        onClick = { vm.setFilter(filter) },
                        label = { Text("${filter.label} (${ui.count(filter)})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            SortRow(ui = ui, onSort = vm::setSort)

            if (ui.filter == AppFilter.SAFE_STOP) {
                Text(
                    text = "Aplicații de utilizator care rulează acum, fără serviciu în prim-plan " +
                        "și fără rol critic (lansator, tastatură, telefon, SMS).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            Spacer(Modifier.height(6.dp))

            val visible = remember(ui.apps, ui.query, ui.filter, ui.sortMode, ui.foregroundPackage) {
                ui.visible()
            }
            if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (ui.apps.isEmpty()) {
                            "Nicio aplicație încărcată.\nApasă pe reîmprospătare."
                        } else {
                            "Nicio aplicație pentru filtrul curent."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    items(
                        items = visible,
                        key = { it.packageName },
                        contentType = { "app" },
                    ) { app ->
                        AppRow(
                            app = app,
                            isForeground = app.packageName == ui.foregroundPackage,
                            showSafety = ui.filter == AppFilter.RUNNING ||
                                ui.filter == AppFilter.BACKGROUND ||
                                ui.filter == AppFilter.SAFE_STOP,
                            onClick = { selectedPackage = app.packageName },
                        )
                    }
                }
            }
        }
    }

    val selected = ui.apps.firstOrNull { it.packageName == selectedPackage }
    if (selected != null) {
        AppDetailSheet(
            app = selected,
            isForeground = selected.packageName == ui.foregroundPackage,
            connected = ui.connected,
            onDismiss = { selectedPackage = null },
            onFreeze = { vm.freeze(selected) },
            onUnfreeze = { vm.unfreeze(selected) },
            onForceStop = { vm.forceStop(selected) },
            onBackup = { vm.backupOnly(selected) },
            onUninstall = { pendingUninstall = selected.packageName },
            onOpen = {
                vm.launchIntent(selected.packageName)?.let { intent ->
                    runCatching { context.startActivity(intent) }
                }
            },
            onCopyPackage = { copyToClipboard(context, selected.packageName) },
        )
    }

    val pendingApp = ui.apps.firstOrNull { it.packageName == pendingUninstall }
    if (pendingApp != null) {
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("Dezinstalezi „${pendingApp.label}”?") },
            text = {
                Column {
                    Text(
                        if (pendingApp.isSystem) {
                            "Aplicație de sistem: va fi eliminată doar pentru utilizatorul curent (0), " +
                                "fără să atingă imaginea de sistem."
                        } else {
                            "Aplicația va fi ștearsă complet de pe telefon."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { backupBeforeUninstall = !backupBeforeUninstall }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = backupBeforeUninstall,
                            onCheckedChange = { backupBeforeUninstall = it },
                        )
                        Column {
                            Text("Copiază APK-ul înainte", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Salvat în Download/Checker/apk-backup",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.uninstall(pendingApp, backupBeforeUninstall)
                        pendingUninstall = null
                    },
                ) {
                    Text("Dezinstalează", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("Anulează") }
            },
        )
    }

    if (showConnection) {
        ConnectionDialog(
            ui = ui,
            onDismiss = { showConnection = false },
            onConnectAdb = { host, port ->
                showConnection = false
                vm.connectAdb(host, port)
            },
            onShizuku = {
                showConnection = false
                vm.requestShizukuPermission()
            },
        )
    }
}

private fun connectionLine(ui: UiState): String = when (val state = ui.connection) {
    is ro.davinci.checker.shell.ShellController.State.Connected -> state.label
    is ro.davinci.checker.shell.ShellController.State.Connecting -> "Mă conectez…"
    is ro.davinci.checker.shell.ShellController.State.Failed -> "Neconectat — ${state.message}"
    else -> "Neconectat"
}

@Composable
private fun ConnectionCard(ui: UiState, onConfigure: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Fără acces privilegiat",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = ui.warning
                    ?: "Conectează-te prin adb sau acordă acces Shizuku ca să vezi ce rulează " +
                        "și ca să poți îngheța, dezgheța sau dezinstala aplicații.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onConfigure) { Text("Configurează conexiunea") }
        }
    }
}

@Composable
private fun ConnectionDialog(
    ui: UiState,
    onDismiss: () -> Unit,
    onConnectAdb: (String, String) -> Unit,
    onShizuku: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(ui.adbHost) }
    var port by rememberSaveable { mutableStateOf(ui.adbPort) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conexiune") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Comenzile rulează ca uid shell, exact ca `adb shell`. " +
                        "Două variante: serverul adb (Termux, implicit 127.0.0.1:5040) " +
                        "sau Shizuku.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("Host adb") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port adb") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ui.connectionError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ui.connectionError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnectAdb(host, port) }) { Text("Conectează adb") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onShizuku) { Text("Cere Shizuku") }
                TextButton(onClick = onDismiss) { Text("Închide") }
            }
        },
    )
}

@Composable
private fun SortRow(ui: UiState, onSort: (SortMode) -> Unit) {
    val runningView = ui.filter == AppFilter.RUNNING ||
        ui.filter == AppFilter.BACKGROUND ||
        ui.filter == AppFilter.SAFE_STOP

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Sortează:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SortChip("Memorie", ui.effectiveSort() == SortMode.RAM) { onSort(SortMode.RAM) }
        SortChip("Nume", ui.effectiveSort() == SortMode.NAME) { onSort(SortMode.NAME) }

        Spacer(Modifier.weight(1f))

        if (runningView) {
            Text(
                text = AppEntry.formatKb(ui.runningMemoryKb),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SortChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@Composable
private fun safetyColor(safety: StopSafety): Color = when (safety) {
    StopSafety.SAFE -> CheckerGreen
    StopSafety.CAUTION -> CheckerAmber
    StopSafety.NEVER -> CheckerRed
}

@Composable
private fun AppRow(
    app: AppEntry,
    isForeground: Boolean,
    showSafety: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, key1 = app.packageName) {
        value = withContext(Dispatchers.IO) { IconCache.load(context, app.packageName) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(icon, app.label)

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.running) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (isForeground) CheckerAmber else CheckerGreen),
                    )
                }
            }
            MonoText(app.packageName, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Badge(
                    text = if (app.isSystem) "sistem" else "utilizator",
                    color = if (app.isSystem) CheckerViolet else CheckerBlue,
                )
                if (app.isFrozen) Badge("îngheţat", CheckerRed)
                if (isForeground) Badge("prim-plan", CheckerAmber)
                else if (app.running) Badge("fundal", CheckerGreen)
                if (showSafety && app.running) {
                    Badge(app.safety.short, safetyColor(app.safety))
                }
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            if (app.memoryLabel.isNotEmpty()) {
                Text(
                    text = app.memoryLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (app.pidLabel.isNotEmpty()) {
                Text(
                    text = app.pidLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailSheet(
    app: AppEntry,
    isForeground: Boolean,
    connected: Boolean,
    onDismiss: () -> Unit,
    onFreeze: () -> Unit,
    onUnfreeze: () -> Unit,
    onForceStop: () -> Unit,
    onBackup: () -> Unit,
    onUninstall: () -> Unit,
    onOpen: () -> Unit,
    onCopyPackage: () -> Unit,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, key1 = app.packageName) {
        value = withContext(Dispatchers.IO) { IconCache.load(context, app.packageName) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon, app.label, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                    MonoText(app.packageName)
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(
                    if (app.isSystem) "aplicație de sistem" else "aplicație de utilizator",
                    if (app.isSystem) CheckerViolet else CheckerBlue,
                )
                if (app.isFrozen) Badge("îngheţată", CheckerRed)
                when {
                    isForeground -> Badge("în prim-plan", CheckerAmber)
                    app.running -> Badge("rulează în fundal", CheckerGreen)
                    else -> Badge("oprită", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))

            KeyValueRow("UID", app.uid.toString())
            KeyValueRow("Versiune", app.versionName)
            KeyValueRow("Procese", if (app.pids.isEmpty()) "—" else app.pids.joinToString(", "))
            KeyValueRow("Memorie", if (app.rssKb > 0) app.memoryLabel else "—")
            KeyValueRow("Fișiere APK", app.apkPaths.size.toString())
            KeyValueRow("SDK", if (app.targetSdk > 0) "${app.targetSdk} (min ${app.minSdk})" else "—")
            KeyValueRow("Instalat", CheckerViewModel.formatDate(app.installedAt))
            KeyValueRow("Actualizat", CheckerViewModel.formatDate(app.updatedAt))
            KeyValueRow("Sursă", app.installer ?: "necunoscută")
            KeyValueRow(
                "Permisiuni",
                if (app.permissionCount > 0) {
                    "${app.permissionCount} cerute, ${app.dangerousPermissions} periculoase"
                } else {
                    "niciuna"
                },
            )
            KeyValueRow(
                "Interfață",
                if (app.hasLaunchIntent) "are iconiță în sertar" else "fără activitate de lansare",
            )
            KeyValueRow(
                "Serviciu prim-plan",
                if (app.hasForegroundService) "da" else "nu",
            )

            Spacer(Modifier.height(12.dp))

            SafetyCard(app)

            Spacer(Modifier.height(14.dp))

            if (!connected) {
                Text(
                    "Acțiunile au nevoie de adb sau Shizuku.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }

            ActionButton(
                text = if (app.isFrozen) "Dezgheață aplicația" else "Îngheață aplicația",
                icon = if (app.isFrozen) Icons.Default.LockOpen else Icons.Default.Lock,
                enabled = connected,
                onClick = { if (app.isFrozen) onUnfreeze() else onFreeze() },
            )

            if (app.running) {
                ActionButton(
                    text = when (app.safety) {
                        StopSafety.SAFE -> "Oprește procesele"
                        StopSafety.CAUTION -> "Oprește temporar procesele"
                        StopSafety.NEVER -> "Oprește forțat (nerecomandat)"
                    },
                    icon = Icons.Default.Stop,
                    enabled = connected,
                    onClick = onForceStop,
                )
            }

            ActionButton(
                text = "Copiază APK-ul în Download/Checker",
                icon = Icons.Default.Download,
                enabled = connected,
                onClick = onBackup,
            )

            ActionButton(
                text = "Deschide aplicația",
                icon = Icons.Default.PlayArrow,
                enabled = !app.isFrozen,
                onClick = onOpen,
            )

            ActionButton(
                text = "Copiază numele pachetului",
                icon = Icons.Default.Folder,
                enabled = true,
                onClick = onCopyPackage,
            )

            Spacer(Modifier.height(6.dp))

            OutlinedButton(
                onClick = onUninstall,
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Dezinstalează")
            }
        }
    }
}

@Composable
private fun SafetyCard(app: AppEntry) {
    val color = safetyColor(app.safety)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (app.safety == StopSafety.NEVER) {
                        Icons.Default.Block
                    } else if (app.safety == StopSafety.CAUTION) {
                        Icons.Default.Warning
                    } else {
                        Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = color,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = app.safety.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = app.safetyReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("package", text))
}