package ro.davinci.checker.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Backend ADB: vorbește direct protocolul serverului adb peste TCP.
 *
 * Pe telefon, serverul adb poate rula în Termux (implicit portul 5040, din
 * variabila ANDROID_ADB_SERVER_PORT) sau pe un calculator din rețea. Aplicația
 * deschide un socket, alege un transport (`host:transport:<serial>`) și
 * trimite comenzi `shell:`. Comenzile rulează ca uid `shell`, exact ca
 * `adb shell` din scriptul 2procwatch.py.
 *
 * Protocolul:
 *   cerere   = 4 caractere hex (lungimea în octeți) + payload
 *   răspuns  = "OKAY" sau "FAIL" + 4 hex + mesaj
 * Serverul adb tratează **fiecare serviciu pe o conexiune nouă** — inclusiv
 * `host:devices`. De aceea căutarea serialului are socket separat, iar
 * transportul și comanda propriu-zisă împart aceeași conexiune.
 */
class AdbShell(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    serialHint: String? = null,
) : ShellBackend {

    override val kind: ShellBackend.Kind = ShellBackend.Kind.ADB

    @Volatile
    private var serial: String? = serialHint?.takeIf { it.isNotBlank() }

    override val label: String
        get() = "adb $host:$port" + (serial?.let { " · $it" } ?: "")

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 5040
    }

    override fun probe(timeoutMs: Long): Boolean {
        val result = exec("echo checker-ok; id -u", timeoutMs)
        return result.code == 0 && result.out.contains("checker-ok")
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        return try {
            val target = serial ?: discoverSerial(timeoutMs)

            open(timeoutMs).use { socket ->
                val output = socket.getOutputStream()
                val input = socket.getInputStream()

                request(output, "host:transport:$target")
                expectOkay(input)

                request(output, "shell:${Framing.wrap(command)}")
                expectOkay(input)

                Framing.parse(readAll(input))
            }
        } catch (t: Throwable) {
            ShellResult("", describe(t), -1)
        }
    }

    /** `host:devices` pe o conexiune proprie; alege telefonul, nu emulatorul. */
    private fun discoverSerial(timeoutMs: Long): String {
        open(timeoutMs).use { socket ->
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            request(output, "host:devices")
            expectOkay(input)
            val payload = readLengthPrefixed(input)

            val devices = payload.lineSequence()
                .mapNotNull { line ->
                    val cut = line.indexOf('\t')
                    if (cut <= 0) null else line.substring(0, cut) to line.substring(cut + 1).trim()
                }
                .filter { it.second == "device" }
                .toList()

            val chosen = devices.firstOrNull { !it.first.startsWith("emulator-") }
                ?: devices.firstOrNull()
                ?: throw IOException(
                    "Niciun dispozitiv adb disponibil pe $host:$port. " +
                        "Pornește serverul (`adb start-server`) sau conectează telefonul.",
                )

            serial = chosen.first
            return chosen.first
        }
    }

    private fun open(timeoutMs: Long): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs.toInt().coerceIn(1_000, 10_000))
        socket.soTimeout = timeoutMs.toInt().coerceIn(1_000, 120_000)
        return socket
    }

    private fun request(output: OutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        output.write("%04x".format(bytes.size).toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun expectOkay(input: InputStream) {
        val status = readExactly(input, 4).toString(Charsets.US_ASCII)
        if (status == "OKAY") return
        val message = if (status == "FAIL") readLengthPrefixed(input) else status
        throw IOException(message.ifBlank { "Răspuns adb necunoscut: $status" })
    }

    private fun readLengthPrefixed(input: InputStream): String {
        val size = readExactly(input, 4).toString(Charsets.US_ASCII).toIntOrNull(16)
            ?: throw IOException("Cadru adb invalid")
        if (size <= 0) return ""
        return readExactly(input, size).toString(Charsets.UTF_8)
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val chunk = input.read(buffer, read, size - read)
            if (chunk < 0) throw IOException("Conexiune adb închisă prematur")
            read += chunk
        }
        return buffer
    }

    private fun readAll(input: InputStream): String {
        val sink = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (timeout: java.net.SocketTimeoutException) {
                break
            }
            if (read < 0) break
            sink.write(buffer, 0, read)
        }
        return sink.toString(Charsets.UTF_8.name())
    }

    private fun describe(t: Throwable): String = when (t) {
        is java.net.ConnectException ->
            "Nu pot deschide conexiunea către $host:$port. Pornește serverul adb."
        is java.net.SocketTimeoutException -> "Timeout adb ($host:$port)."
        else -> t.message ?: t.javaClass.simpleName
    }
}