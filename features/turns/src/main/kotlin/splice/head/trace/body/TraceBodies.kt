// NEW: V4-457 direct-byte trace reference encoding and selected-record hydration.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.storage.DAY_BODY_MAX_BYTES
import splice.core.storage.DAY_BODY_SUFFIX
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path

// why: version one identifies daily Gear-chunk references; inline legacy body literals have no version tag.
private const val REFERENCE_VERSION = 1
private const val REFERENCE_TAG = "trace_chunks"
private const val UNAVAILABLE_TAG = "unavailable"
private val BODY_FIELDS = mapOf("request" to "body", "response" to "text", "client" to "body", "answer" to "body")

/** JSONL stamps stay inline. Only body literals enter a daily pack, once, directly through the Gear encoder. */
internal class TraceBodies(private val maxPackBytes: Long = DAY_BODY_MAX_BYTES) {
    private var activeFile: Path? = null
    private var activeIndex = TracePackIndex()

    /** Runs on ActivityDays' one file lane. No complete record String or complete encoded body is built. */
    fun encode(record: JsonObject, day: Path): ByteArray {
        val file = companion(day)
        if (file != activeFile) {
            activeFile = file
            activeIndex = TracePackIndex()
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
        TraceBodyReaders().use { hydrate(record, day, it) }

    private fun hydrate(record: JsonObject, day: Path, readers: TraceBodyReaders): JsonObject =
        replace(record) { value -> if (value is JsonObject) resolve(value, day, readers) else value }

    /** A damaged reference costs only its body; sibling bodies and record metadata remain readable. */
    fun selected(record: JsonObject, day: Path, readers: TraceBodyReaders): JsonObject =
        withAvailability(
            replace(record) { value ->
                if (value is JsonObject) {
                    try {
                        resolve(value, day, readers)
                    } catch (_: IOException) {
                        JsonObject(value + (UNAVAILABLE_TAG to JsonPrimitive(true)))
                    }
                } else {
                    value
                }
            },
        )

    private fun resolve(value: JsonObject, day: Path, readers: TraceBodyReaders): JsonElement {
        if (value[REFERENCE_TAG] != JsonPrimitive(REFERENCE_VERSION)) {
            throw IOException("invalid trace body chunk version")
        }
        if (JsonScalars.str(value, UNAVAILABLE_TAG) == "true") return value
        val parts = value["parts"] as? JsonArray ?: throw IOException("invalid trace body chunk parts")
        return readers.of(companion(day)).literal(parts)
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
            val chunks = TraceChunker(pack)
            TraceLiteral.encode(text, chunks)
            chunks.finish().also { activeIndex.literals[text] = it }
        }
        buildJsonObject {
            put(REFERENCE_TAG, REFERENCE_VERSION)
            put("parts", parts)
        }
    } catch (_: TracePackFull) {
        buildJsonObject {
            put(REFERENCE_TAG, REFERENCE_VERSION)
            put(UNAVAILABLE_TAG, true)
            put("truncated", true)
            put("reason", "daily trace body budget exhausted")
        }
    }

    private fun companion(day: Path): Path =
        day.resolveSibling(day.fileName.toString().removeSuffix(".1") + DAY_BODY_SUFFIX)

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
