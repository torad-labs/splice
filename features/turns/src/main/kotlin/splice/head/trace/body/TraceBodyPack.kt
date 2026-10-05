// NEW: V4-457 bounded content-addressed daily trace body pack.
// 2026-10-05: it writes the v2 store only, each chunk a zstd frame, deduplicated on the raw chunk's digest. A pack
// that reaches its budget says so once in daemon.log.
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
import splice.core.util.LogSink
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

private val FORMAT = TracePackFormat.V2
private val HEADER_BYTES = FORMAT.headerBytes

// why: the single file lane must not wait indefinitely for another process's pack writer.
private const val TRACE_PACK_LOCK_WAIT_MS = 1_000L

// why: a ten-millisecond poll bounds lock acquisition overhead on the single file lane.
private const val TRACE_PACK_LOCK_POLL_MS = 10L
private val HEX = HexFormat.of()

/** Capacity is a stored omission marker, not an I/O failure or a successful partial literal. */
internal class TracePackFull : IOException("daily trace body budget exhausted")

/** Says once per pack file that it reached its daily budget, so a day that stops recording bodies is never silent. */
internal class TracePackFullLog(private val log: LogSink, private val maxBytes: Long) {
    private var logged: Path? = null

    fun full(file: Path) {
        if (file == logged) return
        logged = file
        log(
            "[trace] $file reached its daily body budget of ${maxBytes}B; " +
                "new bodies are unavailable until the day ends\n",
        )
    }
}

/** Append-only content-addressed binary entries. The OS page cache, not per-entry force, owns durability. */
internal class TraceBodyPack(
    val file: Path,
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
        FORMAT,
        part.getValue("offset").jsonPrimitive.long,
        part.getValue("bytes").jsonPrimitive.int,
        checkNotNull(JsonScalars.str(part["hash"])),
    )

    fun put(bytes: ByteArray): JsonObject {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = HEX.formatHex(digest)
        index.offset(hash)?.let { offset -> reference(hash, offset, bytes.size).takeIf(::current)?.let { return it } }
        val (header, stored) = FORMAT.encode(bytes, digest)
        val offset = index.end + HEADER_BYTES
        if (offset > maxBytes - stored.size) throw TracePackFull()
        index.admit()
        channel.position(index.end)
        TracePackBytes.write(channel, header)
        TracePackBytes.write(channel, stored)
        val part = reference(hash, offset, bytes.size)
        index.end = offset + stored.size
        index.tail = part
        index.put(hash, offset)
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
        val generation = TracePackBytes.initialize(channel, FORMAT)
        val tailLost = index.tail?.let { !current(it) } == true
        val changed = generation != index.generation || index.end > channel.size()
        if (changed || tailLost) {
            index.clear()
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
        val offset = index.end + HEADER_BYTES
        val entry = FORMAT.entry(channel, offset)
        if (entry == null) {
            channel.truncate(index.end)
            return false
        }
        val part = reference(entry.hash, offset, entry.raw)
        if (!index.full) {
            index.admit()
            index.putIfAbsent(entry.hash, offset)
        }
        index.end = offset + entry.stored
        index.tail = part
        return true
    }

    private fun reference(hash: String, offset: Long, length: Int): JsonObject = buildJsonObject {
        put("hash", hash)
        put("offset", offset)
        put("bytes", length)
    }
}
