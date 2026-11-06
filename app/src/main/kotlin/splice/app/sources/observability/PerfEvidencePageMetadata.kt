package splice.app.sources.observability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.usage.perf.history.PerfCursorQuery
import splice.usage.perf.history.PerfEvidenceQuery

internal object PerfEvidencePageMetadata {
    fun of(manifest: PerfCursorManifest): JsonObject = events(PerfCursorMetadata.of(manifest))

    fun failure(query: PerfEvidenceQuery, status: String, reason: String): JsonObject = events(
        PerfCursorMetadata.failure(PerfCursorQuery(query.fromMs, query.untilMs, key(query)), status, reason),
    )

    fun key(query: PerfEvidenceQuery): String = "observations-v1:${query.head ?: "*"}"

    private fun events(value: JsonObject): JsonObject {
        val fields = value.toMutableMap()
        fields.remove("total_matching")?.let { fields["total_matching_events"] = it }
        fields.remove("known_matching")?.let { fields["known_matching_events"] = it }
        fields["record_unit"] = JsonPrimitive("observation")
        fields["source"] = JsonPrimitive("observations")
        fields["join"] = JsonPrimitive("requestId across all pages; observationId deduplicated")
        return JsonObject(fields)
    }
}
