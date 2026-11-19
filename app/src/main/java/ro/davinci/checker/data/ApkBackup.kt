package ro.davinci.checker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ro.davinci.checker.shell.ShellBackend
import ro.davinci.checker.shell.ShellResult
import ro.davinci.checker.shell.shq
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copiază APK-urile unei aplicații înainte de dezinstalare și le poate
 * reinstala ulterior.
 *
 * De ce prin shell și nu prin `File.copy`: APK-urile stau în
 * `/data/app/~~...==/<pachet>/`, iar aplicația nu are voie să le citească.
 * Shell-ul (uid 2000) le poate citi, așa că face `cp` direct în
 * `/sdcard/Download/Checker/apk-backup/...`, loc vizibil și pentru utilizator.
 *
 * Pentru pachetele cu split-uri (base.apk + split_config.*.apk) reinstalarea
 * folosește o sesiune `pm install-create` / `install-write` / `install-commit`.
 */
class ApkBackup(private val context: Context) {

    data class Entry(
        val packageName: String,
        val label: String,
        val dir: String,
        val files: List<BackupFile>,
        val totalBytes: Long,
        val createdAt: Long,
    )

    data class BackupFile(val name: String, val bytes: Long)

    data class BackupResult(val entry: Entry?, val error: String?)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Copiază toate APK-urile pachetului; întoarce intrarea scrisă în index. */
    fun backup(shell: ShellBackend, packageName: String, label: String): BackupResult {
        val pathsResult = shell.exec("pm path ${shq(packageName)}", 15_000)
        val apkPaths = pathsResult.out.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("package:")) trimmed.removePrefix("package:") else null
            }
            .filter { it.endsWith(".apk") }
            .toList()

        if (apkPaths.isEmpty()) {
            return BackupResult(null, "Nu am găsit niciun APK pentru $packageName.")
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = "$ROOT/${safeSegment(packageName)}/$stamp"

        val copies = apkPaths.map { source ->
            val name = source.substringAfterLast('/')
            "cp -f ${shq(source)} ${shq("$dir/$name")}"
        }

        val command = (listOf("mkdir -p ${shq(dir)}") + copies + listOf(
            "chmod 644 ${shq(dir)}/*.apk",
            "ls -l ${shq(dir)}",
        )).joinToString(" && ")

        val result = shell.exec(command, 120_000)
        if (!result.ok) {
            return BackupResult(null, "Copierea APK a eșuat: ${result.err.ifBlank { result.out }}")
        }

        val files = parseLs(result.out)
        if (files.isEmpty()) {
            return BackupResult(null, "Nu am putut citi fișierele copiate în $dir.")
        }

        val entry = Entry(
            packageName = packageName,
            label = label,
            dir = dir,
            files = files,
            totalBytes = files.sumOf { it.bytes },
            createdAt = System.currentTimeMillis(),
        )
        saveEntry(entry)
        return BackupResult(entry, null)
    }

    /** Dezinstalează pachetul; pentru aplicațiile de sistem doar pentru userul 0. */
    fun uninstall(shell: ShellBackend, packageName: String, isSystem: Boolean): ShellResult =
        if (isSystem) {
            shell.exec("pm uninstall --user 0 ${shq(packageName)}", 90_000)
        } else {
            shell.exec("pm uninstall ${shq(packageName)}", 90_000)
        }

    fun delete(shell: ShellBackend, entry: Entry): ShellResult {
        removeEntry(entry.dir)
        return shell.exec("rm -rf ${shq(entry.dir)}", 30_000)
    }

    /** Reinstalează un backup (o singură bucată sau o sesiune cu split-uri). */
    fun restore(shell: ShellBackend, entry: Entry): ShellResult {
        if (entry.files.isEmpty()) {
            return ShellResult("", "Backup-ul nu conține fișiere.", -1)
        }

        val apks = entry.files.filter { it.name.endsWith(".apk") }
        if (apks.isEmpty()) {
            return ShellResult("", "Backup-ul nu conține fișiere .apk.", -1)
        }

        // `pm install` rulează în system_server, care nu poate citi căile FUSE
        // din /sdcard („Unable to open file … u:object_r:fuse:s0”). De aceea
        // copiem întâi APK-urile în /data/local/tmp, unde shell-ul are acces.
        val stage = "$STAGE_PREFIX-${System.currentTimeMillis()}"
        val copy = shell.exec(
            (
                listOf("mkdir -p ${shq(stage)}") +
                    apks.map { apk ->
                        "cp -f ${shq("${entry.dir}/${apk.name}")} ${shq("$stage/${apk.name}")}"
                    } +
                    listOf("chmod 644 ${shq(stage)}/*.apk")
                ).joinToString(" && "),
            180_000,
        )
        if (!copy.ok) {
            return ShellResult(copy.out, "Pregătirea instalării a eșuat: ${copy.text()}", -1)
        }

        return try {
            install(shell, stage, apks)
        } finally {
            shell.exec("rm -rf ${shq(stage)}", 30_000)
        }
    }

    /** Instalează din [dir] un singur APK sau o sesiune cu split-uri. */
    private fun install(shell: ShellBackend, dir: String, apks: List<BackupFile>): ShellResult {
        // Cazul simplu: un singur APK.
        if (apks.size == 1) {
            return shell.exec("pm install -r -d ${shq("$dir/${apks[0].name}")}", 180_000)
        }

        // Cazul cu split-uri: sesiune de instalare.
        val total = apks.sumOf { it.bytes }
        val create = shell.exec("pm install-create -r -d -S $total", 60_000)
        val sessionId = SESSION_REGEX.find(create.out)?.groupValues?.get(1)
            ?: return ShellResult(create.out, "Nu pot crea sesiunea de instalare: ${create.text()}", -1)

        apks.forEach { apk ->
            val write = shell.exec(
                "pm install-write -S ${apk.bytes} $sessionId ${shq(apk.name)} ${shq("$dir/${apk.name}")}",
                180_000,
            )
            if (write.code != 0) {
                shell.exec("pm install-abandon $sessionId", 30_000)
                return ShellResult(write.out, "Scrierea ${apk.name} a eșuat: ${write.text()}", -1)
            }
        }

        return shell.exec("pm install-commit $sessionId", 240_000)
    }

    fun backups(): List<Entry> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val json = array.getJSONObject(index)
                val filesArray = json.optJSONArray("files") ?: JSONArray()
                val files = (0 until filesArray.length()).map { fileIndex ->
                    val file = filesArray.getJSONObject(fileIndex)
                    BackupFile(file.getString("name"), file.optLong("bytes"))
                }
                Entry(
                    packageName = json.getString("package"),
                    label = json.optString("label"),
                    dir = json.getString("dir"),
                    files = files,
                    totalBytes = json.optLong("total"),
                    createdAt = json.optLong("created"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntry(entry: Entry) {
        val all = backups().toMutableList()
        all.removeAll { it.dir == entry.dir }
        all.add(0, entry)

        val array = JSONArray()
        all.forEach { item ->
            val files = JSONArray()
            item.files.forEach { file ->
                files.put(JSONObject().put("name", file.name).put("bytes", file.bytes))
            }
            array.put(
                JSONObject()
                    .put("package", item.packageName)
                    .put("label", item.label)
                    .put("dir", item.dir)
                    .put("files", files)
                    .put("total", item.totalBytes)
                    .put("created", item.createdAt),
            )
        }
        prefs.edit().putString(KEY_INDEX, array.toString()).apply()
    }

    private fun removeEntry(dir: String) {
        val remaining = backups().filterNot { it.dir == dir }
        prefs.edit().putString(
            KEY_INDEX,
            JSONArray().also { array ->
                remaining.forEach { item ->
                    val files = JSONArray()
                    item.files.forEach { file ->
                        files.put(JSONObject().put("name", file.name).put("bytes", file.bytes))
                    }
                    array.put(
                        JSONObject()
                            .put("package", item.packageName)
                            .put("label", item.label)
                            .put("dir", item.dir)
                            .put("files", files)
                            .put("total", item.totalBytes)
                            .put("created", item.createdAt),
                    )
                }
            }.toString(),
        ).apply()
    }

    /** `ls -l` toybox: perm link owner grup mărime dată oră nume. */
    private fun parseLs(output: String): List<BackupFile> = output.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) return@mapNotNull null
            val name = parts.last()
            if (!name.endsWith(".apk")) return@mapNotNull null
            val bytes = parts[4].toLongOrNull() ?: return@mapNotNull null
            BackupFile(name, bytes)
        }
        .toList()

    private fun safeSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val ROOT = "/storage/emulated/0/Download/Checker/apk-backup"
        private const val STAGE_PREFIX = "/data/local/tmp/checker-restore"
        private const val PREFS = "checker_backups"
        private const val KEY_INDEX = "index"
        private val SESSION_REGEX = Regex("""\[(\d+)]""")
    }
}