package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.HeadPerfRows
import splice.usage.perf.PerfCommand
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.history.PerfCursorPage
import splice.usage.perf.history.PerfCursorRequest
import splice.usage.perf.history.PerfCursorSource

class PerfQueryCursorTest {
    @Test
    fun `all heads freeze before continuation even when page size is one`() {
        val first = CursorRows(mutableListOf(PerfRow(10, "ok", emptyMap())))
        val second = CursorRows(mutableListOf(PerfRow(10, "ok", emptyMap())))
        val service = service(mapOf("a" to first, "b" to second))
        val query = PerfQuery(0, 100)
        val one = service.rows(query, pageSize = 1)
        assertEquals("2", JsonScalars.str(one, "total_matching"))
        second.live.add(PerfRow(5, "ok", emptyMap()))
        val two = service.rows(query, JsonScalars.str(one, "next_cursor"), 1)
        assertEquals("2", JsonScalars.str(two, "total_matching"))
        assertEquals("true", JsonScalars.str(two, "export_complete"))
        val ids = (one["record_ids"] as JsonArray) + (two["record_ids"] as JsonArray)
        assertEquals(2, ids.distinct().size)
        PerfWorkBudget.release(one)
        PerfWorkBudget.release(two)
    }

    @Test
    fun `CLI JSON is the shared service reply and export ends with completeness metadata`() {
        val service = service(mapOf("a" to CursorRows(mutableListOf(PerfRow(10, "ok", emptyMap())))))
        val lines = ArrayList<String>()
        val terminal = TerminalOutput { lines.add(it) }
        val command =
            PerfCommand(
                terminal,
                terminal,
                HeadPerfRows { _, _ ->
                    PerfRowsSource {
                        PerfRowsWindow(emptyList())
                    }
                },
                HeadPerfQuery { service },
            )
        assertTrue(
            command.perf(listOf("export", "--from", "0", "--until", "100", "--page-size", "1"), EnvReader { null }),
        )
        val metadata = Json.parseToJsonElement(lines.last()) as JsonObject
        assertEquals("export_metadata", JsonScalars.str(metadata, "record_type"))
        assertEquals("true", JsonScalars.str(metadata, "complete"))
        assertEquals("1", JsonScalars.str(metadata, "records_written"))
        assertFalse(lines.joinToString().contains('\u001b'))
        lines.clear()
        command.perf(listOf("--json", "--from", "0", "--until", "100", "--source", "legacy"), EnvReader { null })
        val expected = service.summary(PerfQuery(0, 100, source = "legacy"))
        assertEquals(expected, Json.parseToJsonElement(lines.single()))
        PerfWorkBudget.release(expected)
    }

    private fun service(sources: Map<String, CursorRows>): PerfQueryService =
        PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = sources.keys
            override fun rows(head: String): PerfRowsSource? = sources[head]
        })

    private class CursorRows(val live: MutableList<PerfRow>) : PerfRowsSource, PerfCursorSource {
        private val snapshots = HashMap<String, List<PerfRow>>()
        override fun window(sinceMs: Long): PerfRowsWindow = PerfRowsWindow(live.filter { it.ts >= sinceMs })
        override fun page(request: PerfCursorRequest): PerfCursorPage {
            val id = request.cursor?.substringBefore(':') ?: "snapshot-${snapshots.size}".also {
                snapshots[it] = live.filter { row -> request.predicate.matches(row) }.toList()
            }
            val offset = request.cursor?.substringAfter(':')?.toInt() ?: 0
            val snapshot = snapshots.getValue(id)
            val rows = snapshot.drop(offset).take(request.pageSize)
            val end = offset + rows.size
            return PerfCursorPage(
                rows,
                rows.indices.map { "$id:${offset + it}" },
                "$id:$end".takeIf { end < snapshot.size },
                buildJsonObject {
                    put("status", "complete")
                    put("snapshot_id", id)
                    put("start_cursor", "$id:0")
                    put("as_of_ms", 100)
                    put("total_matching", snapshot.size)
                },
            )
        }
    }
}
