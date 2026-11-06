package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTag
import splice.core.util.JsonScalars
import splice.usage.perf.history.PerfCursorPage
import splice.usage.perf.history.PerfCursorQuery
import splice.usage.perf.history.PerfCursorRequest
import splice.usage.perf.history.PerfCursorSource
import splice.usage.perf.history.PerfRowPredicate
import splice.usage.perf.query.PerfQueryJson.envelope
import splice.usage.perf.query.PerfQueryJson.numeric
import splice.usage.perf.query.PerfQueryJson.queryError
import splice.usage.perf.query.PerfQueryJson.strings
import splice.usage.perf.query.PerfQueryJson.textValue

/** Each head is frozen before any output. Continuations contain only opaque source cursors. */
internal class PerfQueryRows(private val sources: PerfQuerySources) {
    fun read(query: PerfQuery, filter: PerfQueryFilter, cursor: String?, pageSize: Int): JsonObject {
        val heads = sources.heads().filter { query.filters["head"]?.let { asked -> it == asked } != false }.sorted()
        val problem = headProblem(heads)
        if (problem != null) return problem
        val tokens = cursor?.let { PerfMultiCursor.decode(it, query, heads) }
        if (cursor != null && tokens == null) return queryError("invalid_cursor", "cursor_query_changed_or_malformed")
        val frozen = freeze(query, filter, heads, tokens)
        val failed = frozen.values.firstOrNull { it.start == null }
        return if (failed != null) {
            result(query, frozen, emptyList(), emptyList(), null)
        } else {
            page(query, filter, frozen, pageSize)
        }
    }

    private fun headProblem(heads: List<String>): JsonObject? = when {
        heads.size > MAX_HEADS -> queryError("head_limit", "too_many_heads", "unsupported")
        heads.any { sources.rows(it) !is PerfCursorSource } ->
            queryError("cursor_unavailable", "complete_legacy_row_export_requires_cursor_source", "unsupported")
        else -> null
    }

    private fun freeze(
        query: PerfQuery,
        filter: PerfQueryFilter,
        heads: List<String>,
        tokens: Map<String, HeadCursor>?,
    ): Map<String, HeadCursor> =
        heads.associateWith { head ->
            val source = sources.rows(head) as? PerfCursorSource
                ?: return@associateWith HeadCursor(null, null, queryError("cursor_unavailable", "source_changed"))
            val old = tokens?.get(head)
            val owner: PerfCursorPage = source.page(request(query, filter, head, old?.start, 1))
            owner.use { page ->
                HeadCursor(
                    JsonScalars.str(page.metadata, "start_cursor"),
                    if (old != null) old.next else JsonScalars.str(page.metadata, "start_cursor"),
                    copyMetadata(page.metadata),
                )
            }
        }

    private fun page(
        query: PerfQuery,
        filter: PerfQueryFilter,
        frozen: Map<String, HeadCursor>,
        size: Int,
    ): JsonObject {
        val rows = ArrayList<JsonObject>()
        val ids = ArrayList<String>()
        val next = frozen.toMutableMap()
        for ((head, state) in frozen) {
            if (rows.size >= size || state.next == null) continue
            val source = sources.rows(head) as? PerfCursorSource
                ?: return queryError("cursor_unavailable", "source_changed")
            val owner: PerfCursorPage = source.page(request(query, filter, head, state.next, size - rows.size))
            owner.use { page ->
                if (JsonScalars.str(page.metadata, "snapshot_id") == null) {
                    next[head] = state.copy(metadata = copyMetadata(page.metadata))
                    return result(query, next, rows, ids, null)
                }
                rows.addAll(page.rows.map { PerfLegacyRows.render(head, it) })
                ids.addAll(page.recordIds.map { "$head:$it" })
                next[head] = state.copy(next = page.nextCursor, metadata = copyMetadata(page.metadata))
            }
        }
        val token = if (next.values.any { it.next != null }) PerfMultiCursor.encode(query, next) else null
        return result(query, next, rows, ids, token)
    }

    private fun copyMetadata(value: JsonObject): JsonObject =
        requireNotNull(JsonScalars.objectOrNull(Json, value.toString()))

    private fun request(
        query: PerfQuery,
        filter: PerfQueryFilter,
        head: String,
        cursor: String?,
        size: Int,
    ): PerfCursorRequest =
        PerfCursorRequest(
            PerfCursorQuery(query.fromMs, query.untilMs, PerfMultiCursor.hash(query)),
            PerfRowPredicate { filter.row(head, it) },
            cursor,
            size,
        )

    private fun result(
        query: PerfQuery,
        heads: Map<String, HeadCursor>,
        rows: List<JsonObject>,
        ids: List<String>,
        next: String?,
    ): JsonObject {
        val statuses = heads.values.map { JsonScalars.str(it.metadata, "status") }
        val total = heads.values.map { JsonScalars.str(it.metadata, "total_matching")?.toLongOrNull() }
        val complete = statuses.all { it == "complete" }
        val data = QueryData(query)
        data.complete = complete
        data.asOf = heads.values.mapNotNull {
            JsonScalars.str(it.metadata, "as_of_ms")?.toLongOrNull()
        }.minOrNull() ?: query.untilMs
        data.truncated = !complete
        data.counts = buildJsonObject { put("record_unit", "legacy_row"); put("population_complete", complete) }
        if (!complete) data.issue(if ("busy" in statuses) "cursor_busy" else "cursor_incomplete_or_expired", "rows")
        return envelope(
            query,
            data,
            buildJsonObject {
                put(
                    "status",
                    if (!complete) {
                        data.status()
                    } else if (total.filterNotNull().sum() == 0L) {
                        "empty"
                    } else {
                        OutcomeTag.OK.wire
                    },
                )
                put("record_unit", "legacy_row")
                put("rows", JsonArray(rows))
                put("record_ids", strings(ids))
                put("next_cursor", textValue(next))
                put("total_matching", numeric(if (total.all { it != null }) total.filterNotNull().sum() else null))
                put("snapshot_id", JsonObject(heads.mapValues { it.value.metadata["snapshot_id"] ?: JsonNull }))
                put("heads", JsonObject(heads.mapValues { it.value.metadata }))
                put("export_complete", complete && next == null)
                put("order", "head_then_committed_source_order")
            },
        )
    }
}

internal data class HeadCursor(val start: String?, val next: String?, val metadata: JsonObject = JsonObject(emptyMap()))
private const val MAX_HEADS = 64
