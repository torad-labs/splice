package splice.observability.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `failed owner becomes unknown while healthy readings stay fresh and recovery clears the issue`() {
        var now = 1_000L
        var failing = false
        var heap = 123
        var connections = 3
        val flaky = PerfRuntimeSource {
            check(!failing) { "private-source-message" }
            measurements("runtime_heap_used_bytes", heap)
        }
        val healthy = PerfRuntimeSource { measurements("runtime_http_connections", connections) }
        RuntimeSampler(
            identity(),
            PerfRecorders.disabled(),
            listOf(flaky, healthy),
            WallClock { now },
            ElapsedClock { now },
        )
            .use { sampler ->
                val initial = sampler.sample()
                failing = true
                connections = 5
                now = 6_000L
                val failed = sampler.sample()
                assertEquals(JsonNull, failed.getValue("measurements").jsonObject["runtime_heap_used_bytes"])
                assertEquals(
                    "5.0",
                    failed.getValue("measurements").jsonObject["runtime_http_connections"]?.jsonPrimitive?.content,
                )
                assertFailedOwner(failed)
                now = 8_000L
                val cached = sampler.runtime()
                assertEquals("6000", cached.getValue("as_of_ms").jsonPrimitive.content)
                assertEquals("2000", cached.getValue("age_ms").jsonPrimitive.content)
                failing = false
                heap = 789
                val recovered = sampler.sample()
                assertEquals(
                    "789.0",
                    recovered.getValue("measurements").jsonObject["runtime_heap_used_bytes"]?.jsonPrimitive?.content,
                )
                assertEquals("0", recovered.getValue("failed_source_entries").jsonPrimitive.content)
                assertFalse(recovered.getValue("issues").toString().contains("runtime_owner_read_failed"))
                val recoveredOwner = recovered.getValue("owner_readings").jsonArray.first().jsonObject
                assertEquals("8000", recoveredOwner.getValue("last_success_ms").jsonPrimitive.content)
                assertEquals("1000", initial.getValue("as_of_ms").jsonPrimitive.content)
                listOf(initial, failed, cached, recovered).forEach(PerfWorkBudget::release)
            }
    }

    @Test
    fun `runtime owner cancellation and fatal errors propagate unchanged`() {
        val cancellation = CancellationException("cancelled")
        val fatal = object : VirtualMachineError("fatal") {}
        for (error in listOf(cancellation, fatal)) {
            RuntimeSampler(
                identity(),
                PerfRecorders.disabled(),
                listOf(PerfRuntimeSource { throw error }),
            ).use { sampler ->
                assertSame(error, assertThrows(error.javaClass) { sampler.sample() })
            }
        }
    }

    @Test
    fun `runtime owner interruption propagates and preserves the interrupt flag`() {
        val interrupted = InterruptedException("interrupted")
        try {
            RuntimeSampler(
                identity(),
                PerfRecorders.disabled(),
                listOf(PerfRuntimeSource { throw interrupted }),
            ).use { sampler ->
                assertSame(interrupted, assertThrows(InterruptedException::class.java) { sampler.sample() })
                assertTrue(Thread.currentThread().isInterrupted)
            }
        } finally {
            Thread.interrupted()
        }
    }

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

    private fun assertFailedOwner(failed: JsonObject) {
        assertEquals("1", failed.getValue("failed_source_entries").jsonPrimitive.content)
        val owner = failed.getValue("owner_readings").jsonArray.first().jsonObject
        assertEquals("failed", owner.getValue("status").jsonPrimitive.content)
        assertEquals("1000", owner.getValue("last_success_ms").jsonPrimitive.content)
        assertEquals("6000", owner.getValue("sampled_at_ms").jsonPrimitive.content)
        assertTrue(failed.getValue("issues").toString().contains("runtime_owner_read_failed"))
        assertFalse(failed.toString().contains("private-source-message"))
        val threads = failed.getValue("measurements").jsonObject.getValue("runtime_platform_threads")
        assertTrue(threads.jsonPrimitive.content.toDouble() > 0)
    }

    private fun measurements(id: String, value: Int): JsonObject = buildJsonObject {
        put("measurements", buildJsonObject { put(id, value) })
    }

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
