package splice.app.peer.codex

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CodexIpcSocketTest {
    @Test
    fun `frames UTF-8 messages with a four-byte little-endian length`(@TempDir dir: Path) {
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val path = dir.resolve("desktop-ipc.sock")
        listener.bind(UnixDomainSocketAddress.of(path))
        val peerResult = CompletableFuture<String>()
        val peer = Thread {
            listener.accept().use { channel ->
                val input = DataInputStream(Channels.newInputStream(channel))
                val header = ByteArray(4).also(input::readFully)
                val expectedLength = "hello desktop ✓".toByteArray(Charsets.UTF_8).size
                assertArrayEquals(
                    byteArrayOf(
                        expectedLength.toByte(),
                        (expectedLength ushr 8).toByte(),
                        (expectedLength ushr 16).toByte(),
                        (expectedLength ushr 24).toByte(),
                    ),
                    header,
                )
                val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                val payload = ByteArray(length).also(input::readFully)
                peerResult.complete(payload.toString(Charsets.UTF_8))

                val reply = """{"type":"broadcast","method":"desktop reply ✓"}""".toByteArray(Charsets.UTF_8)
                val replyHeader = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(reply.size)
                replyHeader.flip()
                val output = Channels.newOutputStream(channel)
                output.write(replyHeader.array())
                output.write(reply)
                output.flush()
            }
        }.apply { isDaemon = true; start() }

        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(path))
        CodexIpcSocket(channel).use { socket ->
            socket.send("hello desktop ✓")
            assertEquals("hello desktop ✓", peerResult.get(SOCKET_REPLY_WAIT_S, TimeUnit.SECONDS))
            assertEquals("""{"type":"broadcast","method":"desktop reply ✓"}""", socket.receive())
        }
        peer.join(TimeUnit.SECONDS.toMillis(SOCKET_REPLY_WAIT_S))
        listener.close()
    }

    @Test
    fun `rejects oversized outbound message before writing`(@TempDir dir: Path) {
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val path = dir.resolve("desktop-ipc.sock")
        listener.bind(UnixDomainSocketAddress.of(path))
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(path))
        listener.accept().use { peer ->
            CodexIpcSocket(channel).use { socket ->
                assertThrows(IOException::class.java) { socket.send("x".repeat(CODEX_IPC_MAX_MESSAGE_BYTES + 1)) }
                peer.configureBlocking(false)
                assertEquals(0, peer.read(ByteBuffer.allocate(4)))
            }
        }
        listener.close()
    }

    @Test
    fun `rejects oversized inbound length before payload allocation`(@TempDir dir: Path) {
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val path = dir.resolve("desktop-ipc.sock")
        listener.bind(UnixDomainSocketAddress.of(path))
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(path))
        listener.accept().use { peer ->
            val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CODEX_IPC_MAX_INBOUND_FRAME_BYTES.toInt() + 1)
            header.flip()
            while (header.hasRemaining()) peer.write(header)
            CodexIpcSocket(channel).use { socket ->
                assertThrows(IOException::class.java) { socket.receive() }
            }
        }
        listener.close()
    }

    @Test
    fun `projects a large stream snapshot without retaining conversation content`(@TempDir dir: Path) {
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val path = dir.resolve("desktop-ipc.sock")
        listener.bind(UnixDomainSocketAddress.of(path))
        val privateText = "private-content-".repeat(600_000)
        val payload = buildString {
            append("""{"type":"broadcast","sourceClientId":"owner","method":"""")
            append("""thread-stream-state-changed","version":11,"params":{"hostId":"local","conversationId":"""")
            append("""$THREAD","change":{"type":"snapshot","revision":1,"conversationState":{""")
            append(""""threadRuntimeStatus":{"type":"active"},"messages":""")
            append(JsonPrimitive(privateText))
            append("}}}}")
        }.toByteArray(Charsets.UTF_8)
        val nextPayload = buildString {
            append("""{"type":"broadcast","method":"client-status-changed","params":{"clientId":"owner","status":"""")
            append("""disconnected"}}""")
        }.toByteArray(Charsets.UTF_8)
        val peer = Thread {
            listener.accept().use { channel ->
                listOf(payload, nextPayload).forEach { frame ->
                    val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(frame.size)
                    header.flip()
                    while (header.hasRemaining()) channel.write(header)
                    val body = ByteBuffer.wrap(frame)
                    while (body.hasRemaining()) channel.write(body)
                }
            }
        }.apply { isDaemon = true; start() }
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(path))
        val projected = CodexIpcSocket(channel).use { socket -> socket.receive() to socket.receive() }
        assertTrue(payload.size > CODEX_IPC_MAX_MESSAGE_BYTES)
        assertTrue(projected.first?.contains("\"type\":\"active\"") == true)
        assertFalse(projected.first?.contains("private-content") == true)
        assertTrue(projected.second?.contains("client-status-changed") == true)
        peer.join(TimeUnit.SECONDS.toMillis(SOCKET_REPLY_WAIT_S))
        listener.close()
    }

    @Test
    fun `skips large unrelated patch values and projects a small status patch`(@TempDir dir: Path) {
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val path = dir.resolve("desktop-ipc.sock")
        listener.bind(UnixDomainSocketAddress.of(path))
        val privateText = "patch-private-content-".repeat(500_000)
        val payload = buildString {
            append("""{"type":"broadcast","sourceClientId":"owner","method":"""")
            append("""thread-stream-state-changed","version":11,"params":{"hostId":"local","conversationId":"""")
            append("""$THREAD","change":{"type":"patches","baseRevision":1,"revision":2,"patches":[""")
            append("""{"op":"replace","path":["turns",0,"text"],"value":""")
            append(JsonPrimitive(privateText))
            append("""},{"value":"idle","path":["threadRuntimeStatus","type"],"op":"replace"}]}}}""")
        }.toByteArray(Charsets.UTF_8)
        val peer = Thread {
            listener.accept().use { channel ->
                val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
                header.flip()
                while (header.hasRemaining()) channel.write(header)
                val body = ByteBuffer.wrap(payload)
                while (body.hasRemaining()) channel.write(body)
            }
        }.apply { isDaemon = true; start() }
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(path))
        val projected = CodexIpcSocket(channel).use { it.receive() }
        assertTrue(payload.size > CODEX_IPC_MAX_MESSAGE_BYTES)
        assertTrue(projected?.contains("\"value\":\"idle\"") == true)
        assertFalse(projected?.contains("patch-private-content") == true)
        peer.join(TimeUnit.SECONDS.toMillis(SOCKET_REPLY_WAIT_S))
        listener.close()
    }

    @Test
    fun `a partial header is an error rather than a clean end of stream`(@TempDir dir: Path) {
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val path = dir.resolve("desktop-ipc.sock")
        listener.bind(UnixDomainSocketAddress.of(path))
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(path))
        listener.accept().use { peer ->
            peer.write(ByteBuffer.wrap(byteArrayOf(1, 2)))
            peer.close()
            CodexIpcSocket(channel).use { socket ->
                assertThrows(IOException::class.java) { socket.receive() }
            }
        }
        listener.close()
    }
}

private const val SOCKET_REPLY_WAIT_S = 5L

private const val THREAD = "019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e5f"
