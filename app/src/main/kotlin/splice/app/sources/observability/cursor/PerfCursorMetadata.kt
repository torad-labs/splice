package splice.app.sources.observability.cursor

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.usage.perf.history.PerfCursorQuery

internal object PerfCursorMetadata {
    fun failure(query: PerfCursorQuery, status: String, reason: String): JsonObject = buildJsonObject {
        put("schema_version", 1)
        put(STATUS_FIELD, status)
        put("as_of_ms", JsonNull)
        put("total_matching", JsonNull)
        put("snapshot_id", JsonNull)
        put("requested_from_ms", query.fromMs)
        put("requested_until_ms", query.untilMs)
        put("truncation", true)
        put("read_errors", buildJsonArray { add(reason) })
        put("coverage", buildJsonObject { put(STATUS_FIELD, "unknown") })
    }

    fun of(manifest: PerfCursorManifest): JsonObject = buildJsonObject {
        val complete = manifest.issues.isEmpty() && manifest.malformed == 0L
        put("schema_version", manifest.schemaVersion)
        put(STATUS_FIELD, if (complete) "complete" else INCOMPLETE)
        put("as_of_ms", manifest.asOfMs)
        if (complete) put("total_matching", manifest.matching) else put("total_matching", JsonNull)
        put("known_matching", manifest.matching)
        put("requested_from_ms", manifest.fromMs)
        put("requested_until_ms", manifest.untilMs)
        put("snapshot_id", manifest.id)
        put("start_cursor", "${manifest.id}:0")
        put("order", "committed_segment_then_byte")
        put("truncation", !complete)
        put("malformed_rows", manifest.malformed)
        put("read_errors", buildJsonArray { manifest.issues.distinct().forEach { add(it) } })
        put(
            "coverage",
            buildJsonObject {
                put(STATUS_FIELD, if (complete) "retained_snapshot" else INCOMPLETE)
                put("historical_completeness", "unknown_before_retained_segments")
                put("segments", manifest.segments.size)
                put("observed_ms", JsonNull)
            },
        )
    }
}

private const val STATUS_FIELD = "status"
private const val INCOMPLETE = "incomplete"
