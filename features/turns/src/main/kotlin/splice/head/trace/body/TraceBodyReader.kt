// NEW: V4-457 selected body hydration with digest and bounds validation.
// 2026-10-05: a reader opens one pack in the format its references name, v1 raw or v2 zstd-framed.
package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.memory.HeapWeights
import splice.core.util.Cancellables
import splice.upstream.memory.JvmHeap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.Reader
import java.nio.file.Path

// why: growing output bytes, UTF-16 decode buffers and the returned literal coexist during hydration.
private const val TRACE_LITERAL_HEAP_FACTOR = 12L

/** Reads only selected body references; missing and corrupt content is never read as empty. */
internal class TraceBodyReader(
    private val file: Path,
    private val format: TracePackFormat,
    private val heap: HeapReservations = JvmHeap.budget,
) : AutoCloseable {
    private var literals: HashMap<JsonArray, JsonPrimitive>? = HashMap()
    private val chunks = TraceChunkRead(file, format, heap)
    private var literalLease = initialLiteralLease()

    private fun initialLiteralLease(): HeapLease {
        var ready = false
        return Cancellables.withCleanup({ if (!ready) chunks.close() }) {
            HeapOwners.charge(checkNotNull(literals), heap, 0L).also { ready = true }
        }
    }

    fun literal(parts: JsonArray): JsonPrimitive {
        val cache = checkNotNull(literals)
        cache[parts]?.let { return it }
        val bytes = parts.sumOf(::literalBytes)
        val weight = HeapJson.add(HeapWeights.multiply(bytes, TRACE_LITERAL_HEAP_FACTOR), HeapJson.text(""))
        val peak = (if (bytes <= Int.MAX_VALUE) heap.reserve(weight) else null) ?: throw HeapCapacityException()
        peak.use {
            val literal = ByteArrayOutputStream(bytes.toInt())
            parts.forEach { literal.write(chunks.decoded(it)) }
            val decoded = decodedLiteral(literal)
            // Only the interned empty singleton needs a distinct owner; parsed nonempty strings already have one.
            val value = if (decoded.content.isEmpty()) JsonPrimitive(String(charArrayOf())) else decoded
            HeapOwners.keep(value.content, peak.split(HeapJson.text(value.content)))
            val metadata = HeapJson.add(literalLease.bytes, HeapJson.add(HeapJson.bytes(parts), HeapJson.text("")))
            if (!literalLease.resize(metadata)) throw HeapCapacityException()
            cache[parts] = value
            return value
        }
    }

    /** No literal cache or boundary collection: one leased, SHA-checked chunk and a persistent UTF-8 decoder. */
    fun open(parts: JsonArray): Reader = TraceLiteralReader(parts, TraceLiteralChunk(chunks::decodedSequential), heap)

    /** Check each bounded chunk without building or caching the multi-megabyte literal. */
    fun validate(
        parts: JsonArray,
        decoder: TraceChunkDecoder = TraceChunkDecoder(TracePackFormat::decode),
        validation: TraceBodyValidation? = null,
    ) {
        val peak = heap.reserve(format.maxStored.toLong() + CHUNK_MAX) ?: throw HeapCapacityException()
        peak.use {
            if (!validLiteral(parts, decoder, validation)) throw invalidLiteral()
        }
    }

    private fun validLiteral(
        parts: JsonArray,
        decoder: TraceChunkDecoder,
        validation: TraceBodyValidation?,
    ): Boolean {
        var literal = TraceLiteralScan()
        parts.forEach { part ->
            if (validation == null) {
                if (!literal.feed(chunks.decoded(part, decoder))) return false
            } else {
                literal = chunks.certificate(part, validation).after(literal) ?: return false
            }
        }
        return literal.ended
    }

    private fun invalidLiteral(): IOException = IOException("invalid trace body chunk literal: $file")

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

    private fun <T : Any> required(value: T?): T =
        value ?: throw IOException("invalid trace body chunk reference: $file")

    /** Release cached references, not String-owned leases or the pack's channel and boundary index. */
    fun clearLiterals() {
        if (literals == null || literalLease.bytes == 0L) return
        // HashMap.clear retains its table; a replacement needs its own Cleaner-bound metadata lease.
        val empty = HashMap<JsonArray, JsonPrimitive>()
        val lease = HeapOwners.charge(empty, heap, 0L)
        literals = empty
        literalLease.close()
        literalLease = lease
    }

    override fun close() {
        Cancellables.withCleanup({ literalLease.close() }) {
            literals = null
            chunks.close()
        }
    }
}
