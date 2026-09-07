// NEW: one result-frame shape and JSON encoder govern worker writes and bridge admission.
package splice.spi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Exact worker protocol encoding shared with pre-accept result-batch validation. */
public object CodeModeProtocol {
    private val json: Json = Json { explicitNulls = true }

    /** Encodes the frame payload, excluding the four-byte transport length prefix. */
    public fun encodeFrame(frame: JsonObject): ByteArray =
        json.encodeToString(JsonObject.serializer(), frame).encodeToByteArray()

    /** Builds a result batch with worker-local ids; callers retain text and result-set validation. */
    public fun resultsFrame(results: List<CodeModeResult>): JsonObject = buildJsonObject {
        put("type", "results")
        putJsonArray("results") {
            results.forEach { result ->
                add(
                    buildJsonObject {
                        put("id", result.id)
                        put("output", result.output)
                        put("isError", result.isError)
                    },
                )
            }
        }
    }

    /** Includes JSON escaping and frame structure in the hard payload-byte ceiling. */
    public fun fitsResultFrame(results: List<CodeModeResult>): Boolean =
        encodeFrame(resultsFrame(results)).size <= CodeModeLimits.MAX_FRAME_BYTES
}
