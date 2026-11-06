package splice.observability.runtime

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfRecorders
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.ElapsedClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock

class RuntimeSamplerTest {
    @Test
    fun `shared owners are counted once and overlapping memory stays unknown`() {
        val first = owner(3, 100)
        val second = owner(7, 200)
        RuntimeSampler(identity(), PerfRecorders.disabled(), listOf(first, first, second)).use { sampler ->
            val result = sampler.sample()
            val values = result.getValue("measurements").jsonObject
            assertEquals("10.0", values.getValue("runtime_http_connections").jsonPrimitive.content)
            assertEquals(JsonNull, values.getValue("runtime_heap_used_bytes"))
            assertEquals(2, result.getValue("owner_readings").jsonArray.size)
            assertEquals("registered_owners_only_partial", result.getValue("owner_coverage").jsonPrimitive.content)
            PerfWorkBudget.release(result)
        }
    }

    @Test
    fun `owner overflow remains usable and reports incomplete coverage`() {
        RuntimeSampler(identity(), PerfRecorders.disabled(), List(10) { owner(1, 100) }).use { sampler ->
            val result = sampler.sample()
            assertEquals(8, result.getValue("owner_readings").jsonArray.size)
            assertEquals("2", result.getValue("omitted_source_entries").jsonPrimitive.content)
            assertTrue(result.getValue("issues").toString().contains("runtime_owner_limit"))
            PerfWorkBudget.release(result)
        }
    }

    @Test
    fun `runtime describes missing capabilities and cached age without inventing zero values`() {
        var now = 1_000L
        val owner = PerfRuntimeSource {
            buildJsonObject {
                put("measurements", buildJsonObject { put("runtime_heap_used_bytes", 1234) })
            }
        }
        RuntimeSampler(
            PerfIdentity(
                "boot",
                "build",
                "version",
                "config",
            ),
            PerfRecorders.disabled(),
            listOf(owner),
            WallClock {
                now
            },
            ElapsedClock { now },
        ).use { sampler ->
            val initial = sampler.sample()
            assertEquals(
                "1234.0",
                initial.getValue("measurements").jsonObject.getValue("runtime_heap_used_bytes").jsonPrimitive.content,
            )
            assertEquals(JsonNull, initial.getValue("measurements").jsonObject["runtime_virtual_threads"])
            now += 5_000
            val cached = sampler.runtime()
            assertEquals("5000", cached.getValue("age_ms").jsonPrimitive.content)
            assertEquals(initial["as_of_ms"], cached["as_of_ms"])
            assertTrue(cached.getValue("capabilities").toString().contains("ThreadMXBean counts platform threads only"))
            PerfWorkBudget.release(initial)
            PerfWorkBudget.release(cached)
        }
    }

    private fun identity() = PerfIdentity("boot", "build", "version", "config")

    private fun owner(connections: Int, heap: Int) = PerfRuntimeSource {
        buildJsonObject {
            put(
                "measurements",
                buildJsonObject {
                    put("runtime_http_connections", connections)
                    put("runtime_heap_used_bytes", heap)
                },
            )
        }
    }
}
