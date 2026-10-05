// NEW: V4-457 direct-byte trace reference encoding and selected-record hydration.
// 2026-10-05: new bodies go to the day's v2 store, and a reference's `trace_chunks` version names the store it
// resolves against, so a v1 day written before then still reads. A full pack is logged once per head and day:
// the 1 GiB packs filled every day from Oct 3 to Oct 5 and nothing said so.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.storage.DAY_BODY_MAX_BYTES
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.upstream.memory.JvmHeap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path

private const val UNAVAILABLE_TAG = "unavailable"
private val BODY_FIELDS = mapOf("request" to "body", "response" to "text", "client" to "body", "answer" to "body")

/** JSONL stamps stay inline. Only body literals enter a daily pack, once, directly through the Gear encoder. */
internal class TraceBodies(
    private val maxPackBytes: Long = DAY_BODY_MAX_BYTES,
    private val heap: HeapBudget = JvmHeap.budget,
    log: LogSink = LogSink(DaemonLog::write),
) {
    private var activeFile: Path? = null
    private var activeIndex = TracePackIndex(heap)
    private val fullLog = TracePackFullLog(log, maxPackBytes)

    /** Runs on ActivityDays' one file lane. No complete record String or complete encoded body is built. */
    fun encode(record: JsonObject, day: Path): ByteArray {
        val file = TracePackFormat.V2.pack(day)
        if (file != activeFile) {
            activeFile = file
            activeIndex = TracePackIndex(heap)
        }
        return TraceBodyPack(file, activeIndex, maxPackBytes).use { pack ->
            val references = replace(record) { value ->
                val text = JsonScalars.str(value) ?: throw IOException("trace body is not a literal")
                literal(text, pack)
            }
            val encoded = ByteArrayOutputStream()
            JsonWire.write(withAvailability(references), encoded)
            encoded.write('\n'.code)
            encoded.toByteArray()
        }
    }

    /** Old inline rows require no companion. New references resolve against the actual selected line's day. */
    fun hydrate(record: JsonObject, day: Path): JsonObject =
        TraceBodyReaders(heap).use { hydrate(record, day, it) }

    private fun hydrate(record: JsonObject, day: Path, readers: TraceBodyReaders): JsonObject =
        replace(record) { value -> if (value is JsonObject) resolve(value, day, readers) else value }

    /** A damaged reference costs only its body; sibling bodies and record metadata remain readable. */
    fun selected(record: JsonObject, day: Path, readers: TraceBodyReaders): JsonObject =
        withAvailability(
            replace(record) { value ->
                if (value is JsonObject) {
                    try {
                        resolve(value, day, readers)
                    } catch (capacity: HeapCapacityException) {
                        throw capacity
                    } catch (_: IOException) {
                        JsonObject(value + (UNAVAILABLE_TAG to JsonPrimitive(true)))
                    }
                } else {
                    value
                }
            },
        )

    private fun resolve(value: JsonObject, day: Path, readers: TraceBodyReaders): JsonElement {
        val format = TracePackFormat.entries.firstOrNull { it.names(value) }
            ?: throw IOException("invalid trace body chunk version")
        if (JsonScalars.str(value, UNAVAILABLE_TAG) == "true") return value
        val parts = value["parts"] as? JsonArray ?: throw IOException("invalid trace body chunk parts")
        return readers.of(format.pack(day), format).literal(parts)
    }

    private fun withAvailability(record: JsonObject): JsonObject {
        val unavailable = BODY_FIELDS.any { (section, field) ->
            val body = (record[section] as? JsonObject)?.get(field) as? JsonObject
            body != null && JsonScalars.str(body, UNAVAILABLE_TAG) == "true"
        }
        return if (unavailable) JsonObject(record + ("body_unavailable" to JsonPrimitive(true))) else record
    }

    private fun literal(text: String, pack: TraceBodyPack): JsonObject = try {
        val parts = pack.cached(text) ?: run {
            val chunks = TraceChunker(pack, heap)
            TraceLiteral.encode(text, chunks, heap)
            chunks.finish().also { activeIndex.cache(text, it) }
        }
        buildJsonObject {
            put(TRACE_REFERENCE_TAG, TracePackFormat.V2.version)
            put("parts", parts)
        }
    } catch (_: TracePackFull) {
        fullLog.full(pack.file)
        buildJsonObject {
            put(TRACE_REFERENCE_TAG, TracePackFormat.V2.version)
            put(UNAVAILABLE_TAG, true)
            put("truncated", true)
            put("reason", "daily trace body budget exhausted")
        }
    }

    private inline fun replace(record: JsonObject, body: (JsonElement) -> JsonElement): JsonObject {
        val fields = record.toMutableMap()
        BODY_FIELDS.forEach { (section, field) ->
            val content = record[section] as? JsonObject
            val value = content?.get(field)
            if (content != null && value != null) {
                fields[section] = JsonObject(content + (field to body(value)))
            }
        }
        return JsonObject(fields)
    }
}
