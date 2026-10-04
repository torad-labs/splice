// NEW: V4-457 bounded content-addressed daily trace body pack.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import splice.core.storage.DAY_BODY_MAX_BYTES
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

// why: each binary entry has a byte length followed by its SHA-256 digest and its content.
private const val TRACE_PACK_DIGEST_BYTES = 32
private const val HEADER_BYTES = TRACE_PACK_HEADER_BYTES

// why: the single file lane must not wait indefinitely for another process's pack writer.
private const val TRACE_PACK_LOCK_WAIT_MS = 1_000L

// why: a ten-millisecond poll bounds lock acquisition overhead on the single file lane.
private const val TRACE_PACK_LOCK_POLL_MS = 10L
private val HEX = HexFormat.of()

/** Capacity is a stored omission marker, not an I/O failure or a successful partial literal. */
internal class TracePackFull : IOException("daily trace body budget exhausted")

/** Only the active day's index is retained by a TraceBodies writer; readers hold no global body cache. */
internal class TracePackIndex {
    var generation: UUID? = null
    var end: Long = TRACE_PACK_START_BYTES.toLong()
    var tail: JsonObject? = null
    val chunks = HashMap<String, JsonObject>()

    // Weak keys reuse queued equal literals without keeping completed request bodies alive.
    val literals = WeakHashMap<String, JsonArray>()
}

/** Append-only content-addressed binary entries. The OS page cache, not per-entry force, owns durability. */
internal class TraceBodyPack(
    private val file: Path,
    private val index: TracePackIndex,
    private val maxBytes: Long = DAY_BODY_MAX_BYTES,
) : AutoCloseable {
    private val channel = FileChannel.open(
        file,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
    )
    private var held: FileLock? = null

    init {
        var ready = false
        Cancellables.withCleanup({ if (!ready) close() }) {
            held = acquire()
            refresh()
            ready = true
        }
    }

    /** Weakly cached literals are reusable only while every referenced entry still matches its bytes. */
    fun cached(text: String): JsonArray? =
        index.literals[text]?.takeIf { parts -> parts.all { current(it as JsonObject) } }

    private fun current(part: JsonObject): Boolean = TracePackBytes.matches(
        channel,
        part.getValue("offset").jsonPrimitive.long,
        part.getValue("bytes").jsonPrimitive.int,
        checkNotNull(JsonScalars.str(part["hash"])),
    )

    fun put(bytes: ByteArray): JsonObject {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = HEX.formatHex(digest)
        index.chunks[hash]?.takeIf(::current)?.let { return it }
        val offset = index.end + HEADER_BYTES
        if (offset > maxBytes - bytes.size) throw TracePackFull()
        val header = ByteBuffer.allocate(HEADER_BYTES).putInt(bytes.size).put(digest).array()
        channel.position(index.end)
        TracePackBytes.write(channel, header)
        TracePackBytes.write(channel, bytes)
        val part = reference(hash, offset, bytes.size)
        index.end = offset + bytes.size
        index.tail = part
        index.chunks[hash] = part
        return part
    }

    override fun close() {
        Cancellables.withCleanup({ channel.close() }) { held?.release() }
    }

    private fun acquire(): FileLock {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TRACE_PACK_LOCK_WAIT_MS)
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return lock
            if (System.nanoTime() >= until) throw IOException("trace body chunk writer lock timed out: $file")
            Thread.sleep(TRACE_PACK_LOCK_POLL_MS)
        }
    }

    private fun refresh() {
        val generation = TracePackBytes.initialize(channel)
        val tailLost = index.tail?.let { !current(it) } == true
        val changed = generation != index.generation || index.end > channel.size()
        if (changed || tailLost) {
            index.chunks.clear()
            index.literals.clear()
            index.end = TRACE_PACK_START_BYTES.toLong()
            index.tail = null
            index.generation = generation
        }
        while (index.end < channel.size()) {
            if (!scanEntry()) break
        }
    }

    /** An unreferenced incomplete final entry is healed before the next append, never fused into it. */
    private fun scanEntry(): Boolean {
        val size = channel.size()
        if (size - index.end < HEADER_BYTES) {
            channel.truncate(index.end)
            return false
        }
        val header = ByteBuffer.wrap(TracePackBytes.read(channel, index.end, HEADER_BYTES))
        val length = header.int
        val next = index.end + HEADER_BYTES + length
        if (length !in 1..CHUNK_MAX || next > size) {
            channel.truncate(index.end)
            return false
        }
        val digest = ByteArray(TRACE_PACK_DIGEST_BYTES)
        header.get(digest)
        val hash = HEX.formatHex(digest)
        val part = reference(hash, index.end + HEADER_BYTES, length)
        index.chunks.putIfAbsent(hash, part)
        index.end = next
        index.tail = part
        return true
    }

    private fun reference(hash: String, offset: Long, length: Int): JsonObject = buildJsonObject {
        put("hash", hash)
        put("offset", offset)
        put("bytes", length)
    }
}
