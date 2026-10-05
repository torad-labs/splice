// NEW: V4-457 selected body hydration with digest and bounds validation.
// 2026-10-05: a reader opens one pack in the format its references name, v1 raw or v2 zstd-framed.
package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.core.memory.HeapWeights
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.upstream.memory.JvmHeap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// why: growing output bytes, UTF-16 decode buffers and the returned literal coexist during hydration.
private const val TRACE_LITERAL_HEAP_FACTOR = 12L

// why: boxed long, hash-set node and table growth for one validated entry boundary.
private const val TRACE_BOUNDARY_HEAP_BYTES = 96L

/** Reads only selected body references; missing and corrupt content is never read as empty. */
internal class TraceBodyReader(
    private val file: Path,
    private val format: TracePackFormat,
    private val heap: HeapBudget = JvmHeap.budget,
) : AutoCloseable {
    private val channel = Cancellables.runCatchingCancellable {
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }.getOrElse { failure -> throw IOException("trace body chunk pack missing or unreadable: $file", failure) }

    private val boundaries = HashSet<Long>()
    private val boundaryLease = HeapOwners.charge(boundaries, heap, 0L)
    private val literals = HashMap<JsonArray, JsonPrimitive>()
    private val literalLease = HeapOwners.charge(literals, heap, 0L)
    private var scanned = TRACE_PACK_START_BYTES.toLong()

    init {
        var ready = false
        Cancellables.withCleanup({ if (!ready) close() }) {
            TracePackBytes.generation(channel, format)
            ready = true
        }
    }

    fun literal(parts: JsonArray): JsonPrimitive {
        literals[parts]?.let { return it }
        val bytes = parts.sumOf(::literalBytes)
        val weight = HeapJson.add(HeapWeights.multiply(bytes, TRACE_LITERAL_HEAP_FACTOR), HeapJson.text(""))
        val peak = heap.reserve(weight) ?: throw HeapCapacityException()
        peak.use {
            val literal = ByteArrayOutputStream()
            parts.forEach { literal.write(chunk(it)) }
            val decoded = decodedLiteral(literal)
            // Copy even empty content: an interned singleton must never retain a per-read reservation.
            val value = JsonPrimitive(String(decoded.content.toCharArray()))
            HeapOwners.keep(value.content, peak.split(HeapJson.text(value.content)))
            val metadata = HeapJson.add(literalLease.bytes, HeapJson.add(HeapJson.bytes(parts), HeapJson.text("")))
            if (!literalLease.resize(metadata)) throw HeapCapacityException()
            literals[parts] = value
            return value
        }
    }

    private fun decodedLiteral(literal: ByteArrayOutputStream): JsonPrimitive =
        Cancellables.runCatchingCancellable {
            val primitive = Json.parseToJsonElement(literal.toString(Charsets.UTF_8)) as? JsonPrimitive
            if (primitive?.isString != true) throw IOException("invalid trace body chunk literal: $file")
            primitive
        }.getOrElse { failure -> throw IOException("invalid trace body chunk literal: $file", failure) }

    private fun literalBytes(element: JsonElement): Long {
        val part = required(element as? JsonObject)
        val length = required((part["bytes"] as? JsonPrimitive)?.intOrNull)
        if (length !in 1..CHUNK_MAX) throw IOException("invalid trace body chunk length: $file")
        return length.toLong()
    }

    private fun chunk(element: JsonElement): ByteArray {
        val part = required(element as? JsonObject)
        val hash = required(JsonScalars.str(part["hash"]))
        val offset = required((part["offset"] as? JsonPrimitive)?.longOrNull)
        val length = required((part["bytes"] as? JsonPrimitive)?.intOrNull)
        bounds(offset, length)
        val header = (if (entry(offset)) format.entry(channel, offset) else null)
            ?.takeIf { it.raw == length && it.hash == hash }
            ?: throw IOException("invalid trace body chunk entry at byte $offset: $file")
        val bytes = format.decode(TracePackBytes.read(channel, offset, header.stored), length)
        if (bytes == null || TracePackBytes.hashOf(bytes) != hash) {
            throw IOException("corrupt trace body chunk at byte $offset: $file")
        }
        return bytes
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
            val needed = (boundaries.size + 1L) * TRACE_BOUNDARY_HEAP_BYTES
            if (!boundaryLease.resize(needed)) throw HeapCapacityException()
            boundaries.add(payload)
            scanned = payload + header.stored
        }
        return offset in boundaries
    }

    private fun <T : Any> required(value: T?): T =
        value ?: throw IOException("invalid trace body chunk reference: $file")

    override fun close() {
        literals.clear()
        channel.close()
    }
}
