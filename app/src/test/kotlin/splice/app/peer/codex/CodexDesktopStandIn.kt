package splice.app.peer.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.DataInputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A local Codex desktop IPC owner for one already-loaded conversation. */
internal class CodexDesktopStandIn(
    private val socketPath: Path,
    private val conversationId: String,
    private val ownerFound: Boolean = true,
    private val dropStartTurnReply: Boolean = false,
) : AutoCloseable {
    val requests = LinkedBlockingQueue<JsonObject>()

    private val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

    @Volatile
    private var connection: SocketChannel? = null

    init {
        Files.createDirectories(socketPath.parent)
        listener.bind(UnixDomainSocketAddress.of(socketPath))
        Thread({ serve() }, "codex-desktop-ipc-test").apply { isDaemon = true; start() }
    }

    fun connect(): SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
        connect(UnixDomainSocketAddress.of(socketPath))
    }

    fun nextRequest(method: String): JsonObject? {
        val deadline = System.nanoTime() + WAIT_SECONDS * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val message = requests.poll(remaining.coerceAtLeast(1L), TimeUnit.NANOSECONDS) ?: return null
            if (message.string("method") == method) return message
        }
        return null
    }

    fun disconnectOwner() {
        connection?.let { channel ->
            write(
                channel,
                buildJsonObject {
                    put("type", "broadcast")
                    put("sourceClientId", "desktop-router")
                    put("method", "client-status-changed")
                    putJsonObject("params") {
                        put("clientId", OWNER_ID)
                        put("status", "disconnected")
                    }
                },
            )
            channel.close()
        }
    }

    override fun close() {
        connection?.close()
        listener.close()
    }

    private fun serve() {
        try {
            while (listener.isOpen) {
                listener.accept().use(::serveConnection)
            }
        } catch (_: IOException) {
            // Closing the stand-in is the fixture's owner-disconnect signal.
        }
    }

    private fun serveConnection(channel: SocketChannel) {
        connection = channel
        try {
            val input = DataInputStream(Channels.newInputStream(channel).buffered())
            while (true) {
                val text = read(input) ?: return
                val message = Json.parseToJsonElement(text).jsonObject
                when (message.string("type")) {
                    "request" -> answer(message, channel)
                    "broadcast" -> onClientBroadcast(message, channel)
                }
            }
        } finally {
            connection = null
        }
    }

    private fun answer(message: JsonObject, channel: SocketChannel) {
        requests.offer(message)
        val method = message.string("method")
        if (method == "thread-follower-start-turn" && dropStartTurnReply) {
            channel.close()
            return
        }
        val result = when (method) {
            "initialize" -> buildJsonObject { put("clientId", "splice-desktop-client") }
            "thread-owner-discovery" -> buildJsonObject { put("supportsUntrustedAppInput", true) }
            "thread-follower-start-turn" -> buildJsonObject {
                putJsonObject("result") {
                    putJsonObject("turn") { put("id", "desktop-turn-1") }
                }
            }
            else -> return
        }
        write(
            channel,
            buildJsonObject {
                put("type", "response")
                put("requestId", message.string("requestId"))
                put("method", method)
                put("resultType", "success")
                if (ownerFound) put("handledByClientId", OWNER_ID)
                put("result", result)
            },
        )
    }

    private fun onClientBroadcast(message: JsonObject, channel: SocketChannel) {
        when (message.string("method")) {
            "thread-stream-following-changed" -> sendSnapshot(message, channel)
            "test-thread-status-active" -> sendActivePatch(channel)
        }
    }

    private fun sendSnapshot(message: JsonObject, channel: SocketChannel) {
        val params = message["params"] as? JsonObject ?: return
        if (params.string("conversationId") != conversationId) return
        write(
            channel,
            buildJsonObject {
                put("type", "broadcast")
                put("sourceClientId", OWNER_ID)
                put("method", "thread-stream-state-changed")
                put("version", 11)
                putJsonObject("params") {
                    put("hostId", "local")
                    put("conversationId", conversationId)
                    putJsonObject("change") {
                        put("type", "snapshot")
                        put("revision", 1)
                        putJsonObject("conversationState") {
                            putJsonObject("threadRuntimeStatus") { put("type", "idle") }
                        }
                    }
                }
            },
        )
    }

    fun publishActiveStatus() {
        connection?.let { channel ->
            write(
                channel,
                buildJsonObject {
                    put("type", "broadcast")
                    put("sourceClientId", "test-driver")
                    put("method", "test-thread-status-active")
                },
            )
        }
    }

    private fun sendActivePatch(channel: SocketChannel) {
        write(
            channel,
            buildJsonObject {
                put("type", "broadcast")
                put("sourceClientId", OWNER_ID)
                put("method", "thread-stream-state-changed")
                put("version", 11)
                putJsonObject("params") {
                    put("hostId", "local")
                    put("conversationId", conversationId)
                    putJsonObject("change") {
                        put("type", "patches")
                        put("baseRevision", 1)
                        put("revision", 2)
                        put(
                            "patches",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("op", "replace")
                                        put(
                                            "path",
                                            buildJsonArray {
                                                add(JsonPrimitive("threadRuntimeStatus"))
                                                add(JsonPrimitive("type"))
                                            },
                                        )
                                        put("value", "active")
                                    },
                                )
                            },
                        )
                    }
                }
            },
        )
    }

    private fun read(input: DataInputStream): String? {
        val first = input.read()
        if (first < 0) return null
        val header = ByteArray(4)
        header[0] = first.toByte()
        input.readFully(header, 1, 3)
        val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        if (length < 0 || length > MAX_MESSAGE_BYTES) throw IOException("invalid IPC frame length")
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun write(channel: SocketChannel, message: JsonObject) {
        val payload = Json.encodeToString(JsonObject.serializer(), message).toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
        header.flip()
        synchronized(channel) {
            while (header.hasRemaining()) channel.write(header)
            val body = ByteBuffer.wrap(payload)
            while (body.hasRemaining()) channel.write(body)
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content

    private companion object {
        const val OWNER_ID = "desktop-owner-1"
        const val WAIT_SECONDS = 20L
        const val MAX_MESSAGE_BYTES = 8_388_608
    }
}
