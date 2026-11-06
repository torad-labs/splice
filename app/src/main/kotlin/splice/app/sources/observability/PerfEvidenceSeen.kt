package splice.app.sources.observability

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Temporary disk hash table deduplicates observation ids without a heap-sized request/id cache. */
internal class PerfEvidenceSeen(private val path: Path) : AutoCloseable {
    private val channel = FileChannel.open(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
    )
    private var count = 0

    init {
        try {
            channel.position(BYTES - 1)
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        } catch (failure: IOException) {
            channel.close()
            Files.deleteIfExists(path)
            throw failure
        }
    }

    fun add(identity: String): Boolean {
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        val first = (ByteBuffer.wrap(hash).int and Int.MAX_VALUE) % SLOTS
        var slot = first
        do {
            val bytes = ByteBuffer.allocate(SLOT_BYTES)
            val offset = slot.toLong() * SLOT_BYTES
            while (bytes.hasRemaining()) {
                if (channel.read(bytes, offset + bytes.position()) <= 0) throw IOException("cursor_dedup_read_error")
            }
            if (bytes.array()[0] == 0.toByte()) return insert(offset, hash)
            if (bytes.array().copyOfRange(1, SLOT_BYTES).contentEquals(hash)) return false
            slot = (slot + 1) % SLOTS
        } while (slot != first)
        throw IOException("cursor_dedup_limit")
    }

    private fun insert(offset: Long, hash: ByteArray): Boolean {
        if (count >= SLOTS / LOAD_DIVISOR) throw IOException("cursor_dedup_limit")
        val bytes = ByteBuffer.allocate(SLOT_BYTES).put(1.toByte()).put(hash)
        bytes.flip()
        while (bytes.hasRemaining()) channel.write(bytes, offset + bytes.position())
        count++
        return true
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }
}

internal const val EVIDENCE_DEDUP_BYTES = 262_144L * 33
private const val SLOTS = 262_144
private const val SLOT_BYTES = 33
private const val LOAD_DIVISOR = 2
private const val BYTES = EVIDENCE_DEDUP_BYTES
