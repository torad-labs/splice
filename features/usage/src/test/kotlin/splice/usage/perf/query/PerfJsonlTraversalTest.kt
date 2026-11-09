package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfCoverage
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.history.PerfEvidenceCursorRequest
import splice.usage.perf.history.PerfEvidenceCursorSource
import splice.usage.perf.history.PerfEvidencePage
import splice.usage.perf.history.PerfEvidenceQuery
import splice.usage.perf.history.PerfEvidenceRead

class PerfJsonlTraversalTest {
    @Test
    fun `nonprogress event cursor stops with explicit incomplete traversal metadata`() {
        val source = object : PerfEvidenceCursorSource {
            override fun read(query: PerfEvidenceQuery): PerfEvidenceRead =
                PerfEvidenceRead(emptyList(), PerfCoverage(query.fromMs, query.untilMs, 0), 100, false)
            override fun page(request: PerfEvidenceCursorRequest): PerfEvidencePage =
                PerfEvidencePage(emptyList(), emptyList(), "same", buildJsonObject { put("status", "complete") })
        }
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun evidence(): PerfEvidenceCursorSource = source
        })
        val lines = ArrayList<String>()
        assertFalse(
            PerfJsonlExport(TerminalOutput { lines.add(it) }, service)
                .execute(PerfQuery(0, 100), mapOf("mode" to "observations")),
        )
        assertEquals(3, lines.size)
        val metadata = Json.parseToJsonElement(lines.last()).jsonObject
        assertEquals("false", JsonScalars.str(metadata, "complete"))
        assertEquals("cursor_non_progress", JsonScalars.str(metadata, "traversal_failure"))
    }
}
