// NEW: V4-457 direct-byte trace reference encoding and selected-record hydration.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.storage.DAY_BODY_SUFFIX
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path

// why: version one identifies daily Gear-chunk references; inline legacy body literals have no version tag.
private const val REFERENCE_VERSION = 1
private const val REFERENCE_TAG = "trace_chunks"
private val BODY_FIELDS = mapOf("request" to "body", "response" to "text", "client" to "body", "answer" to "body")

/** JSONL stamps stay inline. Only body literals enter a daily pack, once, directly through the Gear encoder. */
internal class TraceBodies {
    private var activeFile: Path? = null
    private var activeIndex = TracePackIndex()

    /** Runs on ActivityDays' one file lane. No complete record String or complete encoded body is built. */
    fun encode(record: JsonObject, day: Path): ByteArray {
        val file = companion(day)
        if (file != activeFile) {
            activeFile = file
            activeIndex = TracePackIndex()
        }
        return TraceBodyPack(file, activeIndex).use { pack ->
            val references = replace(record) { value ->
                val text = JsonScalars.str(value) ?: throw IOException("trace body is not a literal")
                literal(text, pack)
            }
            val encoded = ByteArrayOutputStream()
            JsonWire.write(references, encoded)
            encoded.write('\n'.code)
            encoded.toByteArray()
        }
    }

    /** Old inline rows require no companion. New references resolve against the actual selected line's day. */
    fun hydrate(record: JsonObject, day: Path): JsonObject {
        val referenced = BODY_FIELDS.any { (section, field) ->
            (record[section] as? JsonObject)?.get(field) is JsonObject
        }
        if (!referenced) return record
        return TraceBodyReader(companion(day)).use { reader ->
            replace(record) { value ->
                if (value is JsonObject) {
                    if (value[REFERENCE_TAG] != JsonPrimitive(REFERENCE_VERSION)) {
                        throw IOException("invalid trace body chunk version")
                    }
                    val parts = value["parts"] as? JsonArray ?: throw IOException("invalid trace body chunk parts")
                    reader.literal(parts)
                } else {
                    value
                }
            }
        }
    }

    private fun literal(text: String, pack: TraceBodyPack): JsonObject {
        val parts = pack.cached(text) ?: run {
            val chunks = TraceChunker(pack)
            TraceLiteral.encode(text, chunks)
            chunks.finish().also { activeIndex.literals[text] = it }
        }
        return buildJsonObject {
            put(REFERENCE_TAG, REFERENCE_VERSION)
            put("parts", parts)
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
