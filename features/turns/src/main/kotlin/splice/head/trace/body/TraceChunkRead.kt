// NEW: selected pack byte access seals each referenced chunk once without retaining its payload.
package splice.head.trace.body

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

// why: boxed long, hash-set node and table growth for one validated entry boundary.
private const val TRACE_BOUNDARY_HEAP_BYTES = 96L

/** Only the bounded reference fields cross into either certificate owner; unknown JSON stays behind. */
internal data class TraceChunkReference(val offset: Long, val length: Int, val hash: String)

/** One selected read owns its channel, entry boundaries and checked current-byte certificates. */
internal class TraceChunkRead(
    private val file: Path,
    private val format: TracePackFormat,
    private val heap: HeapReservations,
) : AutoCloseable {
    private val channel = Cancellables.runCatchingCancellable {
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }.getOrElse { failure -> throw IOException("trace body chunk pack missing or unreadable: $file", failure) }
    private val boundaries = HashSet<Long>()
    private val boundaryLease = HeapOwners.charge(boundaries, heap, 0L)
    private val checked = HashMap<TraceChunkReference, TraceChunkCertificate>()
    private val checkedLease = HeapOwners.charge(checked, heap, 0L)
    private val digest = MessageDigest.getInstance("SHA-256")
    private val hashes = HexFormat.of()
    private var scratch: ByteArray? = null
    private var scratchLease: HeapLease? = null
    private var scanned = TRACE_PACK_START_BYTES.toLong()
    private var generation: UUID? = null

    init {
        var ready = false
        Cancellables.withCleanup({ if (!ready) close() }) {
            generation = TracePackBytes.generation(channel, format)
            ready = true
        }
    }

    fun decoded(
        element: JsonElement,
        decoder: TraceChunkDecoder = TraceChunkDecoder(TracePackFormat::decode),
    ): ByteArray {
        val stored = stored(reference(element))
        val bytes = decoder.decode(format, stored.bytes, stored.header.raw)
        if (bytes == null || TracePackBytes.hashOf(bytes) != stored.header.hash) {
            throw IOException("corrupt trace body chunk at byte ${stored.offset}: $file")
        }
        return bytes
    }

    /** A persistent hit still hashes current bytes, using one bounded buffer for the whole selected read. */
    fun certificate(element: JsonElement, validation: TraceBodyValidation): TraceChunkCertificate {
        val reference = reference(element)
        checked[reference]?.let { return it }
        val header = header(reference)
        val bytes = scratch()
        val seal = seal(reference.offset, header.stored, bytes)
        val key = TraceChunkKey(file, format, required(generation), reference)
        val proof = validation.certify(key, header, bytes, header.stored, seal)
        if (!checkedLease.resize(HeapJson.add(checkedLease.bytes, proof.bytes))) throw HeapCapacityException()
        checked[reference] = proof
        return proof
    }

    private fun scratch(): ByteArray {
        scratch?.let { return it }
        val lease = heap.reserve(format.maxStored.toLong()) ?: throw HeapCapacityException()
        var kept = false
        try {
            return ByteArray(format.maxStored).also {
                scratch = it
                scratchLease = lease
                kept = true
            }
        } finally {
            if (!kept) lease.close()
        }
    }

    private fun seal(offset: Long, length: Int, bytes: ByteArray): String {
        digest.reset()
        val buffer = ByteBuffer.wrap(bytes, 0, length)
        var at = offset
        while (buffer.hasRemaining()) {
            val from = buffer.position()
            val count = channel.read(buffer, at)
            if (count < 0) throw IOException("trace body chunk is missing at byte $at")
            digest.update(bytes, from, count)
            at += count
        }
        return hashes.formatHex(digest.digest())
    }

    private fun reference(element: JsonElement): TraceChunkReference {
        val part = required(element as? JsonObject)
        return TraceChunkReference(
            required((part["offset"] as? JsonPrimitive)?.longOrNull),
            required((part["bytes"] as? JsonPrimitive)?.intOrNull),
            required(JsonScalars.str(part["hash"])),
        )
    }

    private fun stored(reference: TraceChunkReference): StoredChunk {
        val header = header(reference)
        return StoredChunk(reference.offset, header, TracePackBytes.read(channel, reference.offset, header.stored))
    }

    private fun header(reference: TraceChunkReference): TracePackEntry {
        val (offset, length, hash) = reference
        bounds(offset, length)
        return (if (entry(offset)) format.entry(channel, offset) else null)
            ?.takeIf { it.raw == length && it.hash == hash }
            ?: throw IOException("invalid trace body chunk entry at byte $offset: $file")
    }

    private fun bounds(offset: Long, length: Int) {
        val first = TRACE_PACK_START_BYTES + format.headerBytes
        val inside = offset >= first && offset < channel.size()
        if (!inside || length !in 1..CHUNK_MAX) throw IOException("invalid trace body chunk bounds: $file")
    }

    /** Scan headers, never unselected payloads, to reject references starting inside an entry. */
    private fun entry(offset: Long): Boolean {
        while (scanned < offset) {
            val payload = scanned + format.headerBytes
            val header = format.entry(channel, payload)
                ?: throw IOException("invalid trace body chunk header at byte $scanned: $file")
            if (!boundaryLease.resize((boundaries.size + 1L) * TRACE_BOUNDARY_HEAP_BYTES)) throw HeapCapacityException()
            boundaries.add(payload)
            scanned = payload + header.stored
        }
        return offset in boundaries
    }

    private fun <T : Any> required(value: T?): T =
        value ?: throw IOException("invalid trace body chunk reference: $file")

    override fun close() {
        checked.clear()
        checkedLease.close()
        boundaryLease.close()
        scratchLease?.close()
        scratch = null
        scratchLease = null
        channel.close()
    }

    private data class StoredChunk(val offset: Long, val header: TracePackEntry, val bytes: ByteArray)
}
