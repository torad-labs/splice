package splice.usage.perf.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.OutcomeTag
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.query.PerfQueryJson.queryError
import splice.usage.perf.query.PerfQueryJson.queryJson

internal class PerfJsonlExport(private val output: TerminalOutput, private val service: PerfQueryService) {
    fun execute(query: PerfQuery, parameters: Map<String, String>): Boolean {
        val mode = ExportMode.entries.firstOrNull { it.wire == (parameters["mode"] ?: "legacy_rows") }
        if (mode == null) {
            output.line(queryError("invalid_mode", "unsupported_export_mode").toString())
            return false
        }
        val pageSize = parameters["page_size"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE
        var cursor = parameters["cursor"]
        var count = 0L
        var page: ExportedPage
        do {
            page = writePage(query, mode, cursor, pageSize)
            count += page.count
            val repeated = page.next != null && page.next == cursor
            if (!page.accepted || repeated) break
            cursor = page.next
        } while (cursor != null)
        output.line(metadata(query, mode, count, page.complete).toString())
        return page.complete
    }

    private fun writePage(query: PerfQuery, mode: ExportMode, cursor: String?, size: Int): ExportedPage {
        val page = when (mode) {
            ExportMode.ROWS -> service.rows(query, cursor, size)
            ExportMode.OBSERVATIONS -> service.observations(query, cursor, size)
        }
        return try {
            output.line(page.toString())
            ExportedPage(
                JsonScalars.str(page, "next_cursor"),
                JsonScalars.str(page, "export_complete") == "true",
                JsonScalars.str(page, "status") in setOf(OutcomeTag.OK.wire, "empty", "partial"),
                (page[mode.field] as? JsonArray)?.size ?: 0,
            )
        } finally {
            PerfWorkBudget.release(page)
        }
    }

    private fun metadata(
        query: PerfQuery,
        mode: ExportMode,
        count: Long,
        complete: Boolean,
    ): JsonObject = buildJsonObject {
        put("schema_version", 1)
        put("contract_version", 1)
        put("record_type", "export_metadata")
        put("record_unit", mode.unit)
        put("records_written", count)
        put("complete", complete)
        put("status", if (complete) OutcomeTag.OK.wire else "partial")
        put("query", queryJson(query))
    }
}

private enum class ExportMode(val wire: String, val field: String, val unit: String) {
    ROWS("legacy_rows", "rows", "legacy_row"),
    OBSERVATIONS("observations", "observations", "observation"),
}
private data class ExportedPage(val next: String?, val complete: Boolean, val accepted: Boolean, val count: Int)
private const val DEFAULT_PAGE_SIZE = 200
