package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfObjectiveSet
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.HeadPerfRows
import splice.usage.perf.PerfCommand
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow

class PerfQuerySelectionTest {
    @Test
    fun `named objective resolves once with its revision and unknown or mismatched selections are errors`() {
        val objective = PerfObjectiveSet("latency", "revision-one", completionMs = 100.0)
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun objective(id: String): PerfObjectiveSet? = objective.takeIf { id == it.id }
            override fun objectiveCohort(id: String): Map<String, String> = emptyMap()
        })
        val query =
            requireNotNull(
                PerfQueryParser.parse(mapOf("objective" to "latency", "from" to "0", "until" to "100"), 100).query,
            )
        val known = service.summary(query)
        assertEquals("revision-one", JsonScalars.str(known.getValue("query").jsonObject, "objective_revision"))
        assertEquals("unavailable", JsonScalars.str(known.getValue("goodput").jsonObject, "status"))
        assertEquals("error", JsonScalars.str(service.summary(query.copy(objectiveId = "missing")), "status"))
        assertEquals(
            "error",
            JsonScalars.str(service.summary(query.copy(objective = objective.copy(revision = "old"))), "status"),
        )
        PerfWorkBudget.release(known)
    }

    @Test
    fun `successful profile capabilities without status succeeds while capability errors fail`() {
        val lines = ArrayList<String>()
        val terminal = TerminalOutput { lines.add(it) }
        var response = buildJsonObject { put("schema_version", 1); put("capabilities", buildJsonArray {}) }
        val command = PerfCommand(
            terminal,
            terminal,
            HeadPerfRows { _, _ -> PerfRowsSource { PerfRowsWindow(emptyList()) } },
        )
        assertTrue(
            command.perf(listOf("profile", "capabilities"), EnvReader { null }, null, PerfProfileCommand { response }),
        )
        assertEquals(response, Json.parseToJsonElement(lines.single()))
        response = buildJsonObject { put("status", "error"); put("capabilities", buildJsonArray {}) }
        assertFalse(
            command.perf(listOf("profile", "capabilities"), EnvReader { null }, null, PerfProfileCommand { response }),
        )
    }

    @Test
    fun `schema is available offline without topology and machine profile absence has no ANSI text`() {
        val lines = ArrayList<String>()
        val terminal = TerminalOutput { lines.add(it) }
        val command =
            PerfCommand(terminal, terminal) { _, _ -> PerfRowsSource { PerfRowsWindow(emptyList()) } }
        assertTrue(command.perf(listOf("schema"), EnvReader { null }))
        assertEquals("1", JsonScalars.str(Json.parseToJsonElement(lines.single()).jsonObject, "contract_version"))
        lines.clear()
        assertFalse(command.perf(listOf("profile", "start"), EnvReader { null }))
        assertEquals("unsupported", JsonScalars.str(Json.parseToJsonElement(lines.single()).jsonObject, "status"))
        assertFalse(lines.single().contains('\u001b'))
    }
}
