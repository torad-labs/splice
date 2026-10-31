// A Codex app-server as a codex peer meets it: a WebSocket on the Codex home's control socket that answers initialize,
// reads one loaded thread, takes a turn/start, then reports the thread busy and, when the test says so, closed.
package splice.app.peer.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** [status] is the thread's status type thread/read reports: `notLoaded` for a thread this app-server does not hold. */
internal class CodexStandInAppServer(
    socket: Path,
    private val thread: String,
    private val status: String = "idle",
) : AutoCloseable {
    /** The params of the turn/start the peer sent. */
    val turnStart = CompletableFuture<JsonObject>()

    /** Completed by the test once it has seen what it waits for: the server then reports the thread closed. */
    val closeThread = CompletableFuture<Unit>()

    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

    init {
        Files.createDirectories(socket.parent)
        server.bind(UnixDomainSocketAddress.of(socket))
        // A plain thread, not the common pool: the test JVM may run that pool with no workers.
        Thread { serve() }.apply { isDaemon = true }.start()
    }

    override fun close() {
        server.close()
    }

    private fun serve() {
        server.accept().use { channel ->
            val input = DataInputStream(Channels.newInputStream(channel).buffered())
            val output = Channels.newOutputStream(channel)
            upgrade(input, output)
            var message = read(input)
            while (message != null) {
                answer(Json.parseToJsonElement(message).jsonObject, output)
                message = read(input)
            }
        }
    }

    private fun answer(message: JsonObject, output: OutputStream) {
        val id = message["id"]?.toString()
        val reply = { result: String -> send(output, """{"jsonrpc":"2.0","id":$id,"result":$result}""") }
        val tell = { method: String, params: String -> send(output, """{"method":"$method","params":$params}""") }
        when (message["method"]?.toString()?.trim('"')) {
            "initialize" -> reply("{}")
            "thread/read" -> reply("""{"thread":{"id":"$thread","status":{"type":"$status"},"turns":[]}}""")
            "turn/start" -> {
                turnStart.complete(message["params"]?.jsonObject)
                reply("""{"turn":{"id":"turn-1","status":"inProgress","items":[]}}""")
                tell("thread/status/changed", """{"threadId":"$thread","status":{"type":"active","activeFlags":[]}}""")
                closeThread.get(WAIT_S, TimeUnit.SECONDS)
                tell("thread/closed", """{"threadId":"$thread"}""")
            }
            else -> Unit
        }
    }

    private fun upgrade(input: DataInputStream, output: OutputStream) {
        val request = ByteArrayOutputStream()
        while (!request.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) request.write(input.read())
        val key = request.toString(Charsets.US_ASCII).lines()
            .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
        )
        val answer = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(answer.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** One masked client text frame's text; null once the client closed or hung up. */
    private fun read(input: DataInputStream): String? {
        val first = input.read()
        if (first < 0) return null
        val second = input.readUnsignedByte()
        val length = when (val short = second and 0x7F) {
            126 -> input.readUnsignedShort()
            127 -> input.readLong().toInt()
            else -> short
        }
        val mask = ByteArray(4).also(input::readFully)
        val payload = ByteArray(length).also(input::readFully)
        val text = ByteArray(length) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        return if (first and 0x0F == 0x8) null else text.toString(Charsets.UTF_8)
    }

    private fun send(output: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = when {
            bytes.size < 126 -> byteArrayOf(0x81.toByte(), bytes.size.toByte())
            else -> byteArrayOf(0x81.toByte(), 126, (bytes.size shr 8).toByte(), bytes.size.toByte())
        }
        output.write(head + bytes)
        output.flush()
    }
}

private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// why: a deadline for the test's own steps, long enough for a loaded machine.
internal const val WAIT_S = 30L
