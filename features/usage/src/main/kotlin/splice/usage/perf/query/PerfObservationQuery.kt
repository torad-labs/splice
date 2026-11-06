package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfProjection
import splice.core.util.JsonScalars
import splice.usage.perf.history.PerfEvidenceCursorRequest
import splice.usage.perf.history.PerfEvidenceCursorSource
import splice.usage.perf.history.PerfEvidencePage
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfObservationPredicate
import splice.usage.perf.query.PerfQueryJson.envelope
import splice.usage.perf.query.PerfQueryJson.identityJson
import splice.usage.perf.query.PerfQueryJson.queryError
import splice.usage.perf.query.PerfQueryJson.scopeJson
import splice.usage.perf.query.PerfQueryJson.strings
import splice.usage.perf.query.PerfQueryJson.textValue

internal class PerfObservationQuery(private val sources: PerfQuerySources) {
    fun read(query: PerfQuery, filter: PerfQueryFilter, cursor: String?, pageSize: Int): JsonObject {
        val source = sources.evidence() as? PerfEvidenceCursorSource
            ?: return queryError(
                "evidence_cursor_unavailable",
                "observation_export_requires_cursor_source",
                "unsupported",
            )
        val sourceCursor = cursor?.let { PerfObservationCursor.decode(it, query) }
        if (cursor != null && sourceCursor == null) return queryError("invalid_cursor", "observation_query_changed")
        val request =
            PerfEvidenceCursorRequest(
                PerfEvidenceQuery(query.fromMs, query.untilMs, query.filters["head"]),
                sourceCursor,
                pageSize,
                PerfObservationPredicate { filter.scope(it.scope) && filter.links(it.scope, it.links) },
                PerfMultiCursor.hash(query),
            )
        return source.page(request).use { page -> render(query, page) }
    }

    private fun render(query: PerfQuery, page: PerfEvidencePage): JsonObject {
        val selected = page.observations
        val complete = JsonScalars.str(page.metadata, "status") == "complete"
        val data = QueryData(query)
        data.complete = complete
        data.asOf = JsonScalars.str(page.metadata, "as_of_ms")?.toLongOrNull() ?: query.untilMs
        data.truncated = !complete
        if (!complete) data.issue("observation_page_incomplete", "evidence")
        return envelope(
            query,
            data,
            buildJsonObject {
                put(
                    "status",
                    if (complete) OutcomeTag.OK.wire else data.status(),
                )
                put("record_unit", "observation")
                put("observations", JsonArray(selected.map(::observationJson)))
                put("record_ids", strings(selected.map { it.stamp.observationId }))
                put("next_cursor", textValue(page.nextCursor?.let { PerfObservationCursor.encode(it, query) }))
                put("metadata", requireNotNull(JsonScalars.objectOrNull(Json, page.metadata.toString())))
                put(
                    "count_semantics",
                    "metadata_counts_frozen_matching_observation_events",
                )
                put("export_complete", complete && page.nextCursor == null)
            },
        )
    }

    private fun observationJson(event: PerfObservation): JsonObject = buildJsonObject {
        put("observation_id", event.stamp.observationId)
        put("identity", identityJson(event.stamp.identity))
        put("scope", scopeJson(event.scope))
        put("wall_ms", event.stamp.wallMs)
        put("monotonic_ms", event.stamp.monotonicMs)
        put("kind", event.kind.name)
        put("request_id", textValue(event.links.requestId))
        put("attempt_id", textValue(event.links.attemptId))
        put("tool_id", textValue(event.links.toolId))
        put("trace_id", textValue(event.links.traceId))
        put("turn_id", textValue(event.links.turnId))
        put("session_id", textValue(event.links.sessionId))
        put("outcome", textValue(event.outcome))
        put(
            "values",
            JsonObject(
                event.values.filter { (key, value) ->
                    PerfMetricRegistry.permits(key, PerfProjection.EXPORT) && value.isFinite()
                }.mapValues { JsonPrimitive(it.value) },
            ),
        )
    }
}
