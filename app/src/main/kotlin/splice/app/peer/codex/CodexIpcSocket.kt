package splice.app.peer.codex

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

// why: the desktop IPC protocol uses a four-byte little-endian length followed by one UTF-8 JSON message.
private const val IPC_LENGTH_BYTES = 4
private const val IPC_UNSIGNED_LENGTH_MASK = 0xFFFF_FFFFL

// why: outbound requests are small; large conversation snapshots are projected while inbound frames are streamed.
internal const val CODEX_IPC_MAX_MESSAGE_BYTES = 8_388_608

internal class CodexIpcFrameTooLargeException : IOException("a Codex IPC message passed 8 MiB")

/** A length-prefixed desktop IPC connection. The caller owns and closes the channel through this wrapper. */
internal class CodexIpcSocket(private val channel: SocketChannel) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    /** Writes one UTF-8 JSON message, rejecting oversized payloads before any bytes are sent. */
    @Throws(IOException::class)
    fun send(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        if (payload.size > CODEX_IPC_MAX_MESSAGE_BYTES) {
            throw CodexIpcFrameTooLargeException()
        }
        synchronized(writeLock) {
            val header = ByteBuffer.allocate(IPC_LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
            header.flip()
            writeFully(header)
            writeFully(ByteBuffer.wrap(payload))
        }
    }

    fun isOpen(): Boolean = !closed.get() && channel.isOpen && channel.isConnected

    /** Reads one bounded frame and returns its projected JSON, or null when the peer closes between frames. */
    @Throws(IOException::class)
    fun receive(): String? {
        val header = ByteBuffer.allocate(IPC_LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val first = channel.read(header)
        if (first < 0) return null
        readFully(header)
        header.flip()
        val length = header.int.toLong() and IPC_UNSIGNED_LENGTH_MASK
        if (length > CODEX_IPC_MAX_INBOUND_FRAME_BYTES) {
            throw IOException("a Codex IPC frame passed 256 MiB")
        }
        return try {
            CodexIpcProjection.read(channel, length)
        } catch (_: IOException) {
            throw IOException("the desktop IPC frame could not be projected")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) channel.close()
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) < 0) throw EOFException("the Codex IPC connection closed while writing")
        }
    }

    private fun readFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("the Codex IPC message ended early")
        }
    }
}
