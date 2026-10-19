package splice.head.trace

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.head.trace.body.TraceBodyReaders
import splice.head.trace.body.traceBodyFields
import java.io.IOException
import java.nio.file.Path

// Bounded keys/previews, parser scratch and escaped HTTP text coexist within this reservation.
private const val DOCUMENT_PAGE_HEAP_BYTES = 2L shl 20

// why: caps JSON-pointer components so a lookup cannot request unbounded nesting.
private const val DOCUMENT_PATH_DEPTH = 64

// why: bounds the input scanned and split before the pointer becomes path components.
private const val DOCUMENT_PATH_CHARS = 4_096

public data class TraceDocumentQuery(
    val turn: String?,
    val record: String?,
    val section: String?,
    val path: String?,
    val offset: String?,
)

/** Opens just one selected body through its existing checksum-validating stream. */
internal class TraceDocumentRead(private val heap: HeapReservations) {
    suspend fun read(turn: TracedTurn, dir: Path, query: TraceDocumentQuery): String {
        val offset = cursor(query.offset)
        val path = pointer(query.path.orEmpty())
        val (value, day) = body(turn, query)
        val context = currentCoroutineContext()
        val lease = heap.reserve(DOCUMENT_PAGE_HEAP_BYTES) ?: throw HeapCapacityException()
        return lease.use {
            TraceBodyReaders(heap).use { readers ->
                val input = readers.open(value, dir.resolve(day)) ?: throw IOException("document body is unavailable")
                input.use {
                    val page = TraceDocumentNode(TraceDocumentText(it, context)).read(path, offset)
                    JsonWire.string(page).also { text -> HeapOwners.keep(text, lease.split(HeapJson.text(text))) }
                }
            }
        }
    }

    private fun cursor(text: String?): Int {
        val offset = text?.toIntOrNull() ?: if (text == null) 0 else -1
        require(offset >= 0) { "invalid document cursor" }
        return offset
    }

    private fun body(turn: TracedTurn, query: TraceDocumentQuery): Pair<JsonElement, String> {
        val record = selected(turn, query.record)
        val section = query.section.orEmpty()
        val key = traceBodyFields[section] ?: throw IllegalArgumentException("invalid document section")
        val part = record[section] as? JsonObject
        val value = part?.get(key)
        val day = JsonScalars.str(record, TRACE_SOURCE_DAY)
        if (value == null || day == null) throw IOException("document body was not recorded")
        return value to day
    }

    private fun selected(turn: TracedTurn, record: String?): JsonObject = when {
        record == "turn" -> turn.turn ?: throw IOException("turn has not ended")
        record?.startsWith("attempt:") == true -> {
            val index = record.removePrefix("attempt:").toIntOrNull()
            index?.let { turn.attempts.getOrNull(it) } ?: throw IllegalArgumentException("invalid document attempt")
        }
        else -> throw IllegalArgumentException("invalid document record")
    }

    private fun pointer(path: String): List<String> {
        require(path.length <= DOCUMENT_PATH_CHARS) { "document path is too long" }
        if (path.isEmpty()) return emptyList()
        require(path.startsWith('/') && !Regex("~(?:[^01]|$)").containsMatchIn(path)) { "invalid document path" }
        return path.drop(1).split('/').also {
            require(it.size <= DOCUMENT_PATH_DEPTH) { "document path is nested too deeply" }
        }.map { it.replace("~1", "/").replace("~0", "~") }
    }
}
