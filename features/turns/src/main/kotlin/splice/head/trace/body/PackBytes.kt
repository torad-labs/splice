// NEW: V4-457 shared positional pack reads and full append writes.
package splice.head.trace.body

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

// why: one pack header stores an integer length and a 32-byte SHA-256 digest.
internal const val TRACE_PACK_HEADER_BYTES: Int = Int.SIZE_BYTES + 32

// why: a pack incarnation begins with one 64-bit format marker and a two-long UUID.
internal const val TRACE_PACK_START_BYTES: Int = Long.SIZE_BYTES * 3

// why: SPLICEB1 identifies the binary pack format independently of JSONL reference metadata.
private const val TRACE_PACK_MAGIC = 0x53504c4943454231L

internal object TracePackBytes {
    /** A corrupt incarnation cannot be indexed; restart it without ever weakening reader validation. */
    fun initialize(channel: FileChannel): UUID {
        if (channel.size() >= TRACE_PACK_START_BYTES) {
            val header = ByteBuffer.wrap(read(channel, 0, TRACE_PACK_START_BYTES))
            if (header.long == TRACE_PACK_MAGIC) return UUID(header.long, header.long)
        }
        val generation = UUID.randomUUID()
        val header = ByteBuffer.allocate(TRACE_PACK_START_BYTES)
            .putLong(TRACE_PACK_MAGIC)
            .putLong(generation.mostSignificantBits)
            .putLong(generation.leastSignificantBits)
        channel.truncate(0)
        channel.position(0)
        write(channel, header.array())
        return generation
    }

    fun generation(channel: FileChannel): UUID {
        val header = ByteBuffer.wrap(read(channel, 0, TRACE_PACK_START_BYTES))
        if (header.long != TRACE_PACK_MAGIC) throw IOException("invalid trace body chunk pack header")
        return UUID(header.long, header.long)
    }

    /** Writer-generated references must still describe the current bytes after a truncate/regrow. */
    fun matches(channel: FileChannel, offset: Long, length: Int, hash: String): Boolean {
        val first = TRACE_PACK_START_BYTES + TRACE_PACK_HEADER_BYTES
        val inside = offset >= first && offset <= channel.size() - length
        if (!inside || length !in 1..CHUNK_MAX) return false
        return headerMatches(channel, offset, length, hash) && hashOf(read(channel, offset, length)) == hash
    }

    fun headerMatches(channel: FileChannel, offset: Long, length: Int, hash: String): Boolean {
        val header = ByteBuffer.wrap(read(channel, offset - TRACE_PACK_HEADER_BYTES, TRACE_PACK_HEADER_BYTES))
        if (header.int != length) return false
        val digest = ByteArray(TRACE_PACK_HEADER_BYTES - Int.SIZE_BYTES)
        header.get(digest)
        return HexFormat.of().formatHex(digest) == hash
    }

    fun hashOf(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun write(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    fun read(channel: FileChannel, offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        var at = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, at)
            if (count < 0) throw IOException("trace body chunk is missing at byte $at")
            at += count
        }
        return bytes
    }
}
