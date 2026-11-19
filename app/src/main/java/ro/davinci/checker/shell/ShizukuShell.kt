package ro.davinci.checker.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Backend Shizuku.
 *
 * Din versiunea 13, API-ul nu mai oferă `Shizuku.newProcess`; comenzile se
 * rulează printr-un [ShellUserService] legat cu `Shizuku.bindUserService`.
 * Shizuku pornește serviciul într-un proces cu uid `shell`, deci acesta are
 * exact privilegiile `adb shell`.
 */
class ShizukuShell(private val context: Context) : ShellBackend {

    override val kind: ShellBackend.Kind = ShellBackend.Kind.SHIZUKU
    override val label: String = "Shizuku (uid shell)"

    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun permissionGranted(): Boolean = runCatching {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun probe(timeoutMs: Long): Boolean {
        if (!permissionGranted()) return false
        if (!ensureBound(timeoutMs)) return false
        val result = exec("echo checker-ok; id -u", timeoutMs)
        return result.code == 0 && result.out.contains("checker-ok")
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult = synchronized(lock) {
        val remote = bound ?: return ShellResult("", "Serviciul Shizuku nu e conectat.", -1)

        val latch = CountDownLatch(1)
        var code = -1
        var output = ""

        replyHandler = object : Handler(replyThread.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != ShellUserService.MSG_RESULT) return
                code = msg.data.getInt(ShellUserService.KEY_CODE, -1)
                output = msg.data.getString(ShellUserService.KEY_OUTPUT).orEmpty()
                latch.countDown()
            }
        }
        val handler = replyHandler!!

        try {
            val message = Message.obtain(null, ShellUserService.MSG_EXEC).apply {
                data = Bundle().apply {
                    putString(ShellUserService.KEY_COMMAND, command)
                    putInt(ShellUserService.KEY_TIMEOUT, timeoutMs.toInt())
                }
                replyTo = Messenger(handler)
            }
            remote.send(message)

            val finished = latch.await(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
            if (!finished) ShellResult("", "Timeout Shizuku ($timeoutMs ms)", -1)
            else ShellResult(output, "", code)
        } catch (t: Throwable) {
            ShellResult("", t.message ?: t.javaClass.simpleName, -1)
        }
    }

    override fun close() {
        // Legătura rămâne activă pe durata procesului: Shizuku refuză
        // rebind-ul repetat pe aceleași argumente.
    }

    private fun ensureBound(timeoutMs: Long): Boolean {
        bound?.let { return true }

        val latch = CountDownLatch(1)
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound = binder?.let { Messenger(it) }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bound = null
            }
        }

        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellUserService::class.java.name),
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(1)

            Shizuku.bindUserService(args, connection!!)
            latch.await(timeoutMs.coerceAtLeast(5_000), TimeUnit.MILLISECONDS)
            bound != null
        } catch (t: Throwable) {
            bound = null
            false
        }
    }

    private val lock = Any()
    private var replyHandler: Handler? = null

    private companion object {
        @Volatile
        private var bound: Messenger? = null

        @Volatile
        private var connection: ServiceConnection? = null

        private val replyThread: HandlerThread by lazy {
            HandlerThread("checker-shell-replies").apply { start() }
        }
    }
}