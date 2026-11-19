package ro.davinci.checker.shell

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.util.concurrent.TimeUnit

/**
 * Serviciu pornit de Shizuku într-un proces cu uid `shell`.
 *
 * Comunicarea folosește [Messenger] (Binder cu `Message`/`Bundle`), ca să nu
 * depindem de compilatorul `aidl` — pe acest dispozitiv binarul `aidl` din
 * build-tools e x86-64 și nu rulează pe aarch64.
 *
 * Comenzile rulează pe un thread dedicat, ca să nu blocheze main-thread-ul
 * serviciului, iar rezultatul se trimite înapoi prin binder.
 */
class ShellUserService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("checker-shell").apply { start() }
        worker = object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_EXEC) return

                val command = msg.data.getString(KEY_COMMAND).orEmpty()
                val timeoutMs = msg.data.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_MS)
                val replyTo = msg.replyTo

                val (code, output) = runCommand(command, timeoutMs)

                if (replyTo != null) {
                    val reply = Message.obtain(null, MSG_RESULT).apply {
                        data = Bundle().apply {
                            putInt(KEY_CODE, code)
                            putString(KEY_OUTPUT, output)
                        }
                    }
                    runCatching { replyTo.send(reply) }
                }
            }
        }
        messenger = Messenger(worker)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun runCommand(command: String, timeoutMs: Int): Pair<Int, String> {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (output.length < MAX_OUTPUT_CHARS) output.appendRange(buffer, 0, read)
                }
            }

            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return -1 to "$output\nTimeout după $timeoutMs ms: $command"
            }
            process.exitValue() to output.toString()
        } catch (t: Throwable) {
            -1 to (t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        const val MSG_EXEC = 1
        const val MSG_RESULT = 2
        const val KEY_COMMAND = "cmd"
        const val KEY_TIMEOUT = "timeout"
        const val KEY_CODE = "code"
        const val KEY_OUTPUT = "output"
        private const val MAX_OUTPUT_CHARS = 512 * 1024
        private const val DEFAULT_TIMEOUT_MS = 30_000
    }
}