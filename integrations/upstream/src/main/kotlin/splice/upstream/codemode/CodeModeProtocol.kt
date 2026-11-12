// NEW: one result-frame shape and JSON encoder govern worker writes and bridge admission.
package splice.upstream.codemode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
        putResults(this, "results", results)
    }

    /** A bounded result chunk that resumes the guest with the results it contains. */
    public fun resultsPartFrame(results: List<CodeModeResult>): JsonObject = buildJsonObject {
        putResults(this, "results_part", results)
    }

    private fun putResults(builder: JsonObjectBuilder, type: String, results: List<CodeModeResult>) {
        with(builder) {
            put("type", type)
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
    }

    /** Includes JSON escaping and frame structure in the hard payload-byte ceiling. */
    public fun fitsResultFrame(results: List<CodeModeResult>): Boolean =
        encodeFrame(resultsFrame(results)).size <= CodeModeLimits.MAX_FRAME_BYTES

    /** Includes the slightly larger intermediate-frame type tag in the same byte ceiling. */
    public fun fitsResultPartFrame(results: List<CodeModeResult>): Boolean =
        encodeFrame(resultsPartFrame(results)).size <= CodeModeLimits.MAX_FRAME_BYTES

    /** Partitions results into frames that respect both the protocol's per-frame count and byte ceilings. */
    public fun resultChunks(results: List<CodeModeResult>, maxPerFrame: Int): List<List<CodeModeResult>> {
        require(maxPerFrame > 0) { "Result frame count limit must be positive" }
        val chunks = mutableListOf<List<CodeModeResult>>()
        var chunk = mutableListOf<CodeModeResult>()
        results.forEach { result ->
            val candidate = chunk + result
            if (candidate.size > maxPerFrame || !fitsResultPartFrame(candidate)) {
                require(chunk.isNotEmpty()) { "Code-mode result exceeds the frame size limit" }
                chunks += chunk
                chunk = mutableListOf(result)
                require(fitsResultPartFrame(chunk)) { "Code-mode result exceeds the frame size limit" }
            } else {
                chunk = candidate.toMutableList()
            }
        }
        if (chunk.isNotEmpty()) chunks += chunk
        return chunks
    }
}
