package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.PerfRowsSource

class PerfQueryDiagnosisTest {
    @Test
    fun `resource occupancy is ranked only for a runtime sample within the requested interval`() {
        val runtime = buildJsonObject {
            put("as_of_ms", 200)
            put(
                "measurements",
                buildJsonObject {
                    put("runtime_heap_used_bytes", 90)
                    put("runtime_heap_max_bytes", 100)
                    put("runtime_cpu_ratio", 0.5)
                    put("runtime_fd_count", 1)
                    put("runtime_fd_limit", 10)
                },
            )
        }
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun runtime(): PerfRuntimeSource = PerfRuntimeSource { runtime }
        })
        val current = service.diagnose(PerfQuery(100, 300))
        val resources = current.getValue("resources").jsonObject
        assertEquals("sample_in_requested_window", JsonScalars.str(resources, "status"))
        assertEquals(
            listOf("runtime_heap_used_bytes", "runtime_cpu_ratio", "runtime_fd_count"),
            resources.getValue("conditions").jsonArray.map { JsonScalars.str(it.jsonObject, "metric") },
        )
        assertTrue(resources.toString().contains("not_proof_of_saturation_or_request_delay"))
        PerfWorkBudget.release(current)
        listOf(PerfQuery(0, 100), PerfQuery(0, 200)).forEach { historical ->
            val result = service.diagnose(historical)
            val past = result.getValue("resources").jsonObject
            assertEquals("unavailable_for_requested_window", JsonScalars.str(past, "status"))
            assertTrue(past.getValue("conditions").jsonArray.isEmpty())
            assertEquals(runtime, result.getValue("runtime") as JsonObject)
            PerfWorkBudget.release(result)
        }
    }
}
