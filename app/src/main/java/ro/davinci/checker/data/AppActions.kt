package ro.davinci.checker.data

import ro.davinci.checker.shell.ShellBackend
import ro.davinci.checker.shell.shq

/** Acțiuni asupra pachetelor, toate prin shell (adb sau Shizuku). */
class AppActions(private val shell: ShellBackend) {

    data class Result(val ok: Boolean, val message: String)

    /** Îngheață aplicația: `pm disable-user` o oprește și o ascunde din launcher. */
    fun freeze(packageName: String): Result {
        val primary = shell.exec("pm disable-user --user 0 ${shq(packageName)}", 60_000)
        if (primary.pmSuccess) {
            return Result(true, "Aplicația $packageName a fost înghețată.")
        }

        val fallback = shell.exec("pm disable ${shq(packageName)}", 60_000)
        return if (fallback.pmSuccess) {
            Result(true, "Aplicația $packageName a fost înghețată.")
        } else {
            Result(false, "Înghețarea a eșuat: ${primary.text().ifBlank { fallback.text() }}")
        }
    }

    /** Dezgheață aplicația: `pm enable`. */
    fun unfreeze(packageName: String): Result {
        val primary = shell.exec("pm enable ${shq(packageName)}", 60_000)
        if (primary.pmSuccess) {
            return Result(true, "Aplicația $packageName a fost dezghețată.")
        }

        val fallback = shell.exec("pm enable --user 0 ${shq(packageName)}", 60_000)
        return if (fallback.pmSuccess) {
            Result(true, "Aplicația $packageName a fost dezghețată.")
        } else {
            Result(false, "Dezghețarea a eșuat: ${primary.text().ifBlank { fallback.text() }}")
        }
    }

    fun forceStop(packageName: String): Result {
        val result = shell.exec("am force-stop ${shq(packageName)}", 30_000)
        return if (result.code == 0) {
            Result(true, "Procesele aplicației $packageName au fost oprite.")
        } else {
            Result(false, "Oprirea a eșuat: ${result.text()}")
        }
    }

    companion object {
        /**
         * Unele ROM-uri raportează succes fără text „Success”, dar schimbă
         * starea. Verificăm ambele variante.
         */
        fun succeeded(result: Result): Boolean = result.ok
    }
}