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
            channel.position(EVIDENCE_DEDUP_BYTES - 1)
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        } catch (failure: IOException) {
            channel.close()
            Files.deleteIfExists(path)
            throw failure
        }
    }

    fun add(identity: String, payload: ByteArray): PerfEvidenceDuplicate {
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        val first = (ByteBuffer.wrap(hash).int and Int.MAX_VALUE) % SLOTS
        var slot = first
        do {
            val bytes = ByteBuffer.allocate(SLOT_BYTES)
            val offset = slot.toLong() * SLOT_BYTES
            while (bytes.hasRemaining()) {
                if (channel.read(bytes, offset + bytes.position()) <= 0) throw IOException("cursor_dedup_read_error")
            }
            if (bytes.array()[0] == 0.toByte()) return insert(offset, hash, payload)
            if (bytes.array().copyOfRange(1, 1 + EVIDENCE_SHA256_BYTES).contentEquals(hash)) {
                return duplicate(bytes.getInt(1 + EVIDENCE_SHA256_BYTES), payload)
            }
            slot = (slot + 1) % SLOTS
        } while (slot != first)
        throw IOException("cursor_dedup_limit")
    }

    private fun insert(offset: Long, hash: ByteArray, payload: ByteArray): PerfEvidenceDuplicate {
        if (count >= SLOTS / LOAD_DIVISOR) throw IOException("cursor_dedup_limit")
        val content = ByteBuffer.wrap(payload)
        val at = TABLE_BYTES + count.toLong() * EVIDENCE_SHA256_BYTES
        while (content.hasRemaining()) channel.write(content, at + content.position())
        val bytes = ByteBuffer.allocate(SLOT_BYTES).put(1.toByte()).put(hash).putInt(count)
        bytes.flip()
        while (bytes.hasRemaining()) channel.write(bytes, offset + bytes.position())
        count++
        return PerfEvidenceDuplicate.NEW
    }

    private fun duplicate(index: Int, payload: ByteArray): PerfEvidenceDuplicate {
        val bytes = ByteBuffer.allocate(EVIDENCE_SHA256_BYTES)
        val at = TABLE_BYTES + index.toLong() * EVIDENCE_SHA256_BYTES
        while (bytes.hasRemaining()) {
            if (channel.read(bytes, at + bytes.position()) <= 0) throw IOException("cursor_dedup_read_error")
        }
        return if (bytes.array().contentEquals(payload)) PerfEvidenceDuplicate.SAME else PerfEvidenceDuplicate.CONFLICT
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }
}

internal enum class PerfEvidenceDuplicate { NEW, SAME, CONFLICT }

// Size the disk table for 262,144 fixed slots plus one 32-byte payload digest per half-full slot.
internal const val EVIDENCE_DEDUP_BYTES = 262_144L * 37 + 131_072L * 32

// Use 262,144 open-addressed slots while enforcing half occupancy to bound probe pressure.
private const val SLOTS = 262_144

// Each slot contains one occupancy byte, a 32-byte SHA-256 identity and a four-byte payload index.
private const val SLOT_BYTES = 37

// SHA-256 produces thirty-two bytes for both identity keys and payload comparison digests.
private const val EVIDENCE_SHA256_BYTES = 32
private const val TABLE_BYTES = 262_144L * SLOT_BYTES

// Stop inserting at half occupancy so the open-addressed disk table retains empty probe slots.
private const val LOAD_DIVISOR = 2
