// NEW: selected-reference reads preserve availability independently of the trace writer.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.storage.BODY_BUDGET_EVICTED_REASON
import splice.core.storage.DayBodyBudget
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Reference identity, body eviction and literal selection share one read-only boundary. */
internal class TraceBodyResolution(
    private val budget: DayBodyBudget,
    private val decoder: TraceChunkDecoder,
    private val validation: TraceBodyValidation?,
) {
    fun resolve(
        value: JsonObject,
        day: Path,
        readers: TraceBodyReaders,
        selection: TraceBodySelection = TraceBodySelection.RECORDS,
    ): JsonElement {
        val format = TracePackFormat.entries.firstOrNull { it.names(value) }
            ?: throw IOException("invalid trace body chunk version")
        if (JsonScalars.str(value, "unavailable") == "true") return value
        if (budget.evicted(day)) {
            validation?.invalidate(format.pack(day))
            return unavailable(BODY_BUDGET_EVICTED_REASON)
        }
        val parts = value["parts"] as? JsonArray ?: throw IOException("invalid trace body chunk parts")
        return resolveParts(day, readers, format, parts, selection)
    }

    private fun resolveParts(
        day: Path,
        readers: TraceBodyReaders,
        format: TracePackFormat,
        parts: JsonArray,
        selection: TraceBodySelection,
    ): JsonElement = try {
        val reader = readers.of(format.pack(day), format)
        when (selection) {
            TraceBodySelection.RECORDS -> reader.literal(parts)
            TraceBodySelection.SUMMARY -> {
                reader.validate(parts, decoder, validation)
                JsonNull
            }
        }
    } catch (failure: IOException) {
        val pack = format.pack(day)
        if (budget.evicted(day)) {
            validation?.invalidate(pack)
            unavailable(BODY_BUDGET_EVICTED_REASON)
        } else {
            if (!Files.isRegularFile(pack, NOFOLLOW_LINKS)) validation?.invalidate(pack)
            throw failure
        }
    }

    fun unavailable(reason: String): JsonObject = buildJsonObject {
        put(TRACE_REFERENCE_TAG, TracePackFormat.V2.version)
        put("unavailable", true)
        put("truncated", true)
        put("reason", reason)
    }
}
