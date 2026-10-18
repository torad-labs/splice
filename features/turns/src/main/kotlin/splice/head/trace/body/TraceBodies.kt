// NEW: V4-457 direct-byte trace reference encoding and selected-record hydration.
// 2026-10-05: new bodies go to the day's v2 store, and a reference's `trace_chunks` version names the store it
// resolves against, so a v1 day written before then still reads. A full pack is logged once per head and day:
// the 1 GiB packs filled every day from Oct 3 to Oct 5 and nothing said so.
package splice.head.trace.body

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapReservations
import splice.core.memory.HeapWeights
import splice.core.storage.BODY_BUDGET_EVICTED_REASON
import splice.core.storage.DayBodyBudget
import splice.core.storage.DayBodyCapacityException
import splice.core.storage.TRACE_BODY_MAX_BYTES
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.upstream.memory.JvmHeap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private const val UNAVAILABLE_TAG = "unavailable"
internal val traceBodyFields =
    mapOf("request" to "body", "response" to "text", "client" to "body", "answer" to "body")

// why: a part's 64 hex hash chars, 19 offset digits, five length digits, JSON syntax and separator use at most 119 bytes.
private const val TRACE_PART_LINE_BYTES = 128L

// why: 1 KiB exceeds a 28-byte wrapper or the 108-byte unavailable marker, and root flag/newline overhead.
private const val TRACE_REFERENCE_BASE_BYTES = 1024L

// why: the growing byte stream retains under twice the line length and toByteArray retains one more complete copy.
private const val TRACE_LINE_BUFFER_COPIES = 3L

/** JSONL stamps stay inline. Only body literals enter a daily pack, once, directly through the Gear encoder. */
internal class TraceBodies(
    private val maxPackBytes: Long = TRACE_BODY_MAX_BYTES,
    private val heap: HeapReservations = JvmHeap.budget,
    log: LogSink = LogSink(DaemonLog::write),
    // Host free-space policy is injected by TraceStore; standalone codecs only enforce their byte budget.
    private val budget: DayBodyBudget = DayBodyBudget(maxPackBytes, minFreeBytes = 0),
    decoder: TraceChunkDecoder = TraceChunkDecoder(TracePackFormat::decode),
    validation: TraceBodyValidation? = null,
    /** Told when a record is refused in part or whole for capacity, and when one saves whole; null tells no one. */
    private val saves: TraceBodySaves? = null,
) {
    /** Whether the record being encoded lost a body to capacity; encode runs on one file lane, so one flag serves. */
    private var refusedBody = false
    private var activeFile: Path? = null
    private var activeIndex = TracePackIndex(heap)
    private val fullLog = TracePackFullLog(log, maxPackBytes)

    // Its own, so a refused body and a refused index line, which meet the floor together, are each said once.
    private val indexFullLog = TracePackFullLog(log, maxPackBytes)

    private val resolution = TraceBodyResolution(budget, decoder, validation)

    /** Sizes only inline metadata. Body lengths bound the references without escaping or traversing their text. */
    fun encodingBytes(record: JsonObject): Long {
        var references = TRACE_REFERENCE_BASE_BYTES
        val metadata = replace(record) { value ->
            val chars = JsonScalars.str(value)?.length?.toLong() ?: 0L
            // Two quotes surround the literal; every nonfinal part has at least CHUNK_MIN encoded bytes.
            val literal = chars * UNICODE_ESCAPE_BYTES + 2L
            val parts = (literal + CHUNK_MIN - 1) / CHUNK_MIN
            references = HeapJson.add(
                references,
                HeapJson.add(TRACE_REFERENCE_BASE_BYTES, HeapWeights.multiply(parts, TRACE_PART_LINE_BYTES)),
            )
            JsonNull
        }
        val line = HeapJson.add(JsonWire.byteSize(metadata), references)
        val workspace = HeapJson.add(HeapWeights.CONNECTION_BYTES, CHUNK_MAX.toLong() + TracePackFormat.V2.maxStored)
        // The shallow root/section copies also coexist with the line, even when metadata has many tiny fields.
        val metadataCopies = HeapJson.bytes(metadata)
        val buffers = HeapWeights.multiply(line, TRACE_LINE_BUFFER_COPIES)
        return HeapJson.add(metadataCopies, HeapJson.add(buffers, workspace))
    }

    /** Runs on ActivityDays' one file lane. No complete record String or complete encoded body is built. */
    fun encode(record: JsonObject, day: Path): ByteArray {
        val file = TracePackFormat.V2.pack(day)
        if (file != activeFile) {
            activeFile = file
            activeIndex = TracePackIndex(heap)
        }
        refusedBody = false
        return budget.withLock(day.parent) {
            val line = if (budget.evicted(day)) {
                refusedBody = true
                saves?.refused(BODY_BUDGET_EVICTED_REASON)
                encodeReferences(replace(record) { resolution.unavailable(BODY_BUDGET_EVICTED_REASON) })
            } else {
                try {
                    if (!Files.exists(file, NOFOLLOW_LINKS)) budget.admit(file, TRACE_PACK_START_BYTES.toLong())
                    TraceBodyPack(file, activeIndex, maxPackBytes, budget).use { pack ->
                        val references = replace(record) { value ->
                            val text = JsonScalars.str(value) ?: throw IOException("trace body is not a literal")
                            literal(text, pack)
                        }
                        encodeReferences(references)
                    }
                } catch (capacity: DayBodyCapacityException) {
                    refusedBody = true
                    saves?.refused(capacity.reason)
                    fullLog.full(file, capacity.reason)
                    encodeReferences(replace(record) { resolution.unavailable(capacity.reason) })
                }
            }
            // The index obeys the bodies' free-space floor and its own shared cap, which drops the oldest completed day
            // first: a record refused by either is dropped, said once per day.
            budget.admitIndex(day, line.size.toLong())?.let { reason ->
                saves?.refused(reason)
                indexFullLog.full(day, reason)
                throw DayBodyCapacityException(reason)
            }
            if (!refusedBody) saves?.saved()
            line
        }
    }

    private fun encodeReferences(references: JsonObject): ByteArray {
        val encoded = ByteArrayOutputStream()
        JsonWire.write(withAvailability(references), encoded)
        encoded.write('\n'.code)
        return encoded.toByteArray()
    }

    /** Old inline rows require no companion. New references resolve against the actual selected line's day. */
    fun hydrate(record: JsonObject, day: Path): JsonObject = heap.readShare().use { share ->
        TraceBodyReaders(share).use { hydrate(record, day, it) }
    }

    private fun hydrate(record: JsonObject, day: Path, readers: TraceBodyReaders): JsonObject =
        replace(record) { value -> if (value is JsonObject) resolution.resolve(value, day, readers) else value }

    /** A damaged reference costs only its body; sibling bodies and record metadata remain readable. */
    fun selected(
        record: JsonObject,
        day: Path,
        readers: TraceBodyReaders,
        selection: TraceBodySelection = TraceBodySelection.RECORDS,
    ): JsonObject =
        withAvailability(
            replace(record) { value ->
                if (value is JsonObject) {
                    try {
                        resolution.resolve(value, day, readers, selection)
                    } catch (capacity: HeapCapacityException) {
                        throw capacity
                    } catch (_: IOException) {
                        JsonObject(value + (UNAVAILABLE_TAG to JsonPrimitive(true)))
                    }
                } else {
                    if (selection == TraceBodySelection.SUMMARY) JsonNull else value
                }
            },
        ).let { selected ->
            if (selection == TraceBodySelection.SUMMARY) JsonObject(selected - traceBodyFields.keys) else selected
        }

    private fun withAvailability(record: JsonObject): JsonObject {
        val unavailable = traceBodyFields.any { (section, field) ->
            val body = (record[section] as? JsonObject)?.get(field) as? JsonObject
            body != null && JsonScalars.str(body, UNAVAILABLE_TAG) == "true"
        }
        return if (unavailable) JsonObject(record + ("body_unavailable" to JsonPrimitive(true))) else record
    }

    private fun literal(text: String, pack: TraceBodyPack): JsonObject = try {
        val parts = pack.cached(text) ?: run {
            val resume = pack.resume(text)
            val chunks = TraceChunker(pack, heap, resume?.parts.orEmpty(), resume?.skip ?: 0)
            TraceLiteral.encode(text, chunks, heap, resume?.from ?: 0, chunks.cuts)
            chunks.finish().also {
                activeIndex.cache(text, it)
                activeIndex.recent.remember(text, it, chunks.cuts)
            }
        }
        buildJsonObject {
            put(TRACE_REFERENCE_TAG, TracePackFormat.V2.version)
            put("parts", parts)
        }
    } catch (capacity: TracePackFull) {
        refusedBody = true
        saves?.refused(capacity.reason)
        fullLog.full(pack.file, capacity.reason)
        resolution.unavailable(capacity.reason)
    }

    private inline fun replace(record: JsonObject, body: (JsonElement) -> JsonElement): JsonObject {
        val fields = record.toMutableMap()
        traceBodyFields.forEach { (section, field) ->
            val content = record[section] as? JsonObject
            val value = content?.get(field)
            if (content != null && value != null) {
                fields[section] = JsonObject(content + (field to body(value)))
            }
        }
        return JsonObject(fields)
    }
}

/** Who hears that a record lost its bodies, or its line, to capacity, and that one saved whole again. */
public interface TraceBodySaves {
    public fun refused(reason: String)

    public fun saved()
}
