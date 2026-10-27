// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): the WebSocket a codex peer's helper speaks to its Codex
// home's app-server. The app-server's control socket is a Unix socket that speaks WebSocket (RFC 6455), one JSON-RPC
// message per text frame, as the Bun bridge in splice/mcp/codex-socket-proxy.js speaks it for the MCP config; the helper
// is a JVM, so it speaks it itself. The client masks every frame it sends and the server masks none, a ping is
// answered with a pong, a close with a close, and a message past 8 MiB ends the connection.
package splice.app.peer.codex

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// why: the bridge's own cap on one message (codex-socket-proxy.js MAX_BYTES); a thread read with turns stays under it.
private const val MAX_MESSAGE_BYTES = 8_388_608

// why: the bridge's cap on the upgrade answer's headers; an answer longer than this is not the app-server's.
private const val MAX_HANDSHAKE_BYTES = 16_384

// why: RFC 6455 4.1, the client's key is 16 random bytes, base64.
private const val NONCE_BYTES = 16

// why: RFC 6455 5.3, a client frame's mask is 4 bytes.
private const val MASK_BYTES = 4

// why: RFC 6455 5.2, a 7-bit length of 126 means a 16-bit length follows.
private const val LENGTH_16 = 126

// why: RFC 6455 5.2, a 7-bit length of 127 means a 64-bit length follows.
private const val LENGTH_64 = 127

// why: RFC 6455 5.2, the largest length a 16-bit extended length holds.
private const val MAX_LENGTH_16 = 0xFFFF

// why: RFC 6455 5.5, a control frame's payload is at most 125 bytes.
private const val MAX_CONTROL_BYTES = 125

private const val LENGTH_MASK = 0x7F // why: RFC 6455 5.2, the low seven bits of the second byte hold the length.
private const val OPCODE_MASK = 0x0F // why: RFC 6455 5.2, the low four bits of the first byte hold the opcode.
private const val FIN = 0x80 // why: RFC 6455 5.2, the first byte's high bit marks a message's last frame.
private const val MASK_BIT = 0x80 // why: RFC 6455 5.2, the second byte's high bit marks a masked payload.
private const val BYTE = 0xFF // why: one byte's bits, to take a length apart a byte at a time.
private const val SHIFT_BYTE = 8 // why: the bits in a byte, the step between a length's bytes.
private const val OP_TEXT = 0x1 // why: RFC 6455 5.2, the opcode of a text frame.
private const val OP_CLOSE = 0x8 // why: RFC 6455 5.2, the opcode of a close; it and above are control frames.
private const val OP_PING = 0x9 // why: RFC 6455 5.2, the opcode of a ping.
private const val OP_PONG = 0xA // why: RFC 6455 5.2, the opcode of a pong.
private const val ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private const val ACCEPT_HEADER = "Sec-WebSocket-Accept"
private const val SWITCHING = "HTTP/1.1 101"

/** One WebSocket over a connected Unix socket. [send] may be called from any thread; [receive] from one at a time. */
internal class CodexSocket(private val channel: SocketChannel) : AutoCloseable {
    private val input = DataInputStream(Channels.newInputStream(channel).buffered())
    private val output: OutputStream = Channels.newOutputStream(channel)
    private val random = SecureRandom()
    private val ioLock = Any()

    /** Asks for the upgrade and checks the answer: true once the WebSocket is open. */
    fun handshake(): Boolean {
        val key = Base64.getEncoder().encodeToString(ByteArray(NONCE_BYTES).also(random::nextBytes))
        val request = "GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        synchronized(ioLock) {
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        val lines = headers(input).split("\r\n")
        val accept = lines.drop(1)
            .firstOrNull { line -> line.substringBefore(':').trim().equals(ACCEPT_HEADER, ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + ACCEPT_GUID).toByteArray(Charsets.US_ASCII)),
        )
        return lines.first().startsWith(SWITCHING) && accept == expected
    }

    /** Sends [text] as one text frame. */
    fun send(text: String) {
        frame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /** The next whole text message, answering pings on the way; null once the server closed or the socket ended. */
    fun receive(): String? {
        val message = ByteArrayOutputStream()
        while (true) {
            val frame = next() ?: return null
            if (frame.control()) {
                if (!answered(frame)) return null
            } else {
                message.write(frame.payload)
                if (message.size() > MAX_MESSAGE_BYTES) throw IOException("a Codex message passed 8 MiB")
                if (frame.fin) return message.toString(Charsets.UTF_8)
            }
        }
    }

    override fun close() {
        channel.close()
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray) {
        /** A close, a ping or a pong: the opcodes from 8 up. */
        fun control(): Boolean = opcode and OP_CLOSE != 0
    }

    /** Answers a control frame: a pong for a ping, a close for a close. False once the server closed. */
    private fun answered(frame: Frame): Boolean = when (frame.opcode) {
        OP_PING -> {
            frame(OP_PONG, frame.payload)
            true
        }
        OP_CLOSE -> {
            frame(OP_CLOSE, frame.payload)
            false
        }
        else -> true
    }

    /** The next frame, or null at the end of the stream. */
    private fun next(): Frame? {
        val first = try {
            input.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        }
        val second = input.readUnsignedByte()
        val length = when (val short = second and LENGTH_MASK) {
            LENGTH_16 -> input.readUnsignedShort().toLong()
            LENGTH_64 -> input.readLong()
            else -> short.toLong()
        }
        val readable = second and MASK_BIT == 0 && length in 0..MAX_MESSAGE_BYTES
        if (!readable) throw IOException("the Codex app-server sent a frame this client cannot read")
        val payload = ByteArray(length.toInt())
        input.readFully(payload)
        return Frame(first and FIN != 0, first and OPCODE_MASK, payload)
    }

    /** One masked frame, written whole under the lock so frames from two threads never interleave. */
    private fun frame(opcode: Int, payload: ByteArray) {
        val head = ByteArrayOutputStream()
        head.write(FIN or opcode)
        when {
            payload.size < LENGTH_16 -> head.write(MASK_BIT or payload.size)
            payload.size <= MAX_LENGTH_16 -> {
                head.write(MASK_BIT or LENGTH_16)
                head.write(payload.size shr SHIFT_BYTE and BYTE)
                head.write(payload.size and BYTE)
            }
            else -> {
                head.write(MASK_BIT or LENGTH_64)
                for (shift in Long.SIZE_BITS - SHIFT_BYTE downTo 0 step SHIFT_BYTE) {
                    head.write((payload.size.toLong() shr shift and BYTE.toLong()).toInt())
                }
            }
        }
        if (opcode >= OP_CLOSE && payload.size > MAX_CONTROL_BYTES) return
        val mask = ByteArray(MASK_BYTES).also(random::nextBytes)
        head.write(mask)
        val masked = ByteArray(payload.size) { index ->
            (payload[index].toInt() xor mask[index % MASK_BYTES].toInt()).toByte()
        }
        synchronized(ioLock) {
            output.write(head.toByteArray() + masked)
            output.flush()
        }
    }

    /** The upgrade answer's status line and headers, up to the blank line that ends them. */
    private fun headers(stream: InputStream): String {
        val read = ByteArrayOutputStream()
        while (!read.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) {
            val byte = stream.read()
            if (byte < 0 || read.size() > MAX_HANDSHAKE_BYTES) throw IOException("the Codex app-server did not upgrade")
            read.write(byte)
        }
        return read.toString(Charsets.US_ASCII).trimEnd()
    }
}
