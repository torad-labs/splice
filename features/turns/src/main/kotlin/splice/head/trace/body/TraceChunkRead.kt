// NEW: selected pack byte access seals each referenced chunk once without retaining its payload.
package splice.head.trace.body

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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

    /** The per-read view retains only certificates; a persistent hit still requires current compressed bytes. */
    fun certificate(element: JsonElement, validation: TraceBodyValidation): TraceChunkCertificate {
        val reference = reference(element)
        checked[reference]?.let { return it }
        val stored = stored(reference)
        val key = TraceChunkKey(file, format, required(generation), reference)
        val proof = validation.certify(key, stored.header, stored.bytes)
        if (!checkedLease.resize(HeapJson.add(checkedLease.bytes, proof.bytes))) throw HeapCapacityException()
        checked[reference] = proof
        return proof
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
        val (offset, length, hash) = reference
        bounds(offset, length)
        val header = (if (entry(offset)) format.entry(channel, offset) else null)
            ?.takeIf { it.raw == length && it.hash == hash }
            ?: throw IOException("invalid trace body chunk entry at byte $offset: $file")
        return StoredChunk(offset, header, TracePackBytes.read(channel, offset, header.stored))
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
        channel.close()
    }

    private data class StoredChunk(val offset: Long, val header: TracePackEntry, val bytes: ByteArray)
}
