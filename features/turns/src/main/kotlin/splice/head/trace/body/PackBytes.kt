// NEW: V4-457 shared positional pack reads and full append writes.
// 2026-10-05: the two entry formats a pack can hold. V1 stores raw chunks in `.bodies`; V2 stores each chunk as a
// zstd frame in its own `.bodies2` file, so a jar from before it never opens or truncates one.
package splice.head.trace.body

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.storage.DAY_BODY_SUFFIX
import splice.core.storage.DAY_BODY_V2_SUFFIX
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

// why: every entry header ends with its raw chunk's 32-byte SHA-256 digest.
private const val TRACE_PACK_DIGEST_BYTES = 32

// why: one pack header stores an integer length and a 32-byte SHA-256 digest.
internal const val TRACE_PACK_HEADER_BYTES: Int = Int.SIZE_BYTES + TRACE_PACK_DIGEST_BYTES

// why: a v2 entry header stores its zstd frame's length, its raw chunk's length and that chunk's SHA-256 digest.
internal const val TRACE_PACK_V2_HEADER_BYTES: Int = Int.SIZE_BYTES * 2 + TRACE_PACK_DIGEST_BYTES

// why: a pack incarnation begins with one 64-bit format marker and a two-long UUID.
internal const val TRACE_PACK_START_BYTES: Int = Long.SIZE_BYTES * 3

// why: SPLICEB1 identifies the binary pack format independently of JSONL reference metadata.
private const val TRACE_PACK_MAGIC = 0x53504c4943454231L

// why: SPLICEB2 marks a zstd-framed pack, so neither format's reader ever reads the other's entries as its own.
private const val TRACE_PACK_V2_MAGIC = 0x53504c4943454232L

// why: zstd's default level; on the 2026-10-05 packs it stored trace chunks about three times smaller, at a cost
// the one file lane does not notice.
private const val TRACE_PACK_ZSTD_LEVEL = 3

/** The JSONL field naming the store a body reference resolves against, by its [TracePackFormat.version]. */
internal const val TRACE_REFERENCE_TAG = "trace_chunks"

/** One entry's header: the bytes it stores, the raw chunk they decode to and that chunk's digest. */
internal data class TracePackEntry(val stored: Int, val raw: Int, val hash: String)

/** How a pack stores its entries. V1 is the raw store every trace day before 2026-10-05 wrote, and is only read now;
 *  V2 is the store new bodies go to. A body reference's `trace_chunks` is its store's [version]. */
internal enum class TracePackFormat(
    val version: Int,
    val suffix: String,
    val magic: Long,
    val headerBytes: Int,
    val maxStored: Int,
) {
    V1(1, DAY_BODY_SUFFIX, TRACE_PACK_MAGIC, TRACE_PACK_HEADER_BYTES, CHUNK_MAX),
    V2(
        2,
        DAY_BODY_V2_SUFFIX,
        TRACE_PACK_V2_MAGIC,
        TRACE_PACK_V2_HEADER_BYTES,
        Zstd.compressBound(CHUNK_MAX.toLong()).toInt(),
    ),
    ;

    /** The header of the whole entry whose content starts at [payload], or null when none starts and ends there. */
    fun entry(channel: FileChannel, payload: Long): TracePackEntry? {
        val size = channel.size()
        if (payload < TRACE_PACK_START_BYTES + headerBytes || payload > size) return null
        val header = ByteBuffer.wrap(TracePackBytes.read(channel, payload - headerBytes, headerBytes))
        val stored = header.int
        val raw = if (this == V1) stored else header.int
        val digest = ByteArray(TRACE_PACK_DIGEST_BYTES)
        header.get(digest)
        val sized = stored in 1..maxStored && raw in 1..CHUNK_MAX
        val whole = sized && payload <= size - stored
        return if (whole) TracePackEntry(stored, raw, HEX.formatHex(digest)) else null
    }

    /** The file this format keeps [day]'s bodies in; a rolled day shares its day's pack. */
    fun pack(day: Path): Path = day.resolveSibling(day.fileName.toString().removeSuffix(".1") + suffix)

    /** Whether [reference] resolves against this store, by its [TRACE_REFERENCE_TAG]. */
    fun names(reference: JsonObject): Boolean = reference[TRACE_REFERENCE_TAG] == JsonPrimitive(version)

    /** The bytes a v2 entry stores for [raw], and the header in front of them. */
    fun encode(raw: ByteArray, digest: ByteArray): Pair<ByteArray, ByteArray> {
        check(this == V2) { "a v1 pack is read, never written" }
        val stored = Zstd.compress(raw, TRACE_PACK_ZSTD_LEVEL)
        val header = ByteBuffer.allocate(headerBytes).putInt(stored.size).putInt(raw.size).put(digest).array()
        return header to stored
    }

    /** The raw chunk an entry stores, or null when its bytes do not decode to exactly [raw] bytes. */
    fun decode(stored: ByteArray, raw: Int): ByteArray? = when (this) {
        V1 -> stored.takeIf { it.size == raw }
        V2 -> try {
            Zstd.decompress(stored, raw).takeIf { it.size == raw }
        } catch (_: ZstdException) {
            null
        }
    }
}

private val HEX = HexFormat.of()

internal object TracePackBytes {
    /** A corrupt incarnation cannot be indexed; restart it without ever weakening reader validation. */
    fun initialize(channel: FileChannel, format: TracePackFormat): UUID {
        if (channel.size() >= TRACE_PACK_START_BYTES) {
            val header = ByteBuffer.wrap(read(channel, 0, TRACE_PACK_START_BYTES))
            if (header.long == format.magic) return UUID(header.long, header.long)
        }
        val generation = UUID.randomUUID()
        val header = ByteBuffer.allocate(TRACE_PACK_START_BYTES)
            .putLong(format.magic)
            .putLong(generation.mostSignificantBits)
            .putLong(generation.leastSignificantBits)
        channel.truncate(0)
        channel.position(0)
        write(channel, header.array())
        return generation
    }

    fun generation(channel: FileChannel, format: TracePackFormat): UUID {
        val header = ByteBuffer.wrap(read(channel, 0, TRACE_PACK_START_BYTES))
        if (header.long != format.magic) throw IOException("invalid trace body chunk pack header")
        return UUID(header.long, header.long)
    }

    /** Writer-generated references must still describe the current bytes after a truncate/regrow. */
    fun matches(channel: FileChannel, format: TracePackFormat, offset: Long, length: Int, hash: String): Boolean {
        val entry = format.entry(channel, offset)?.takeIf { it.raw == length && it.hash == hash } ?: return false
        return format.decode(read(channel, offset, entry.stored), length)?.let(::hashOf) == hash
    }

    fun hashOf(bytes: ByteArray): String =
        HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

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
