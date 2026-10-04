// V4-213: HeadStatus wrote the gate's counters and live rows as literals (0 and []). This pins the
// projection of what the head measured onto the console's GateSnapshot wire shape
// (console/src/shared/api GateSnapshot and GateLive): field names, phase words, and order kept.
package splice.heads

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.head.GateHealth
import splice.core.head.GatePhase
import splice.core.head.GateSlot
import splice.core.head.Head
import splice.core.head.HeadHealth

private class MeasuredHead(
    private val health: HeadHealth,
    private val rates: splice.core.head.RateLimitHealth? = null,
    private val answer: splice.core.head.ProviderAnswer? = null,
) : Head {
    override val key: String = "claudex"
    override val label: String = "claudex"
    override val port: Int = 3099
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override fun healthSnapshot(): HeadHealth = health
    override fun rateLimitSnapshot(): splice.core.head.RateLimitHealth? = rates
    override fun providerAnswer(): splice.core.head.ProviderAnswer? = answer
}

class HeadStatusTest {

    @Test
    fun `rate turns are unit-bearing fields and an uninstrumented head stays unknown`() {
        val measured = measured(emptyList())
        val head = MeasuredHead(measured, splice.core.head.RateLimitHealth(4, 6))
        val health = HeadStatus.json(head, "client").getValue("health").jsonObject
        assertEquals("4", health.getValue("provider_rate_limit_turns").jsonPrimitive.content)
        assertEquals("6", health.getValue("cooldown_held_turns").jsonPrimitive.content)
        val unknown = HeadStatus.json(MeasuredHead(measured), "client").getValue("health").jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, unknown["provider_rate_limit_turns"])
        assertEquals(kotlinx.serialization.json.JsonNull, unknown["cooldown_held_turns"])
    }

    @Test
    fun `provider readiness carries the original answer and its measured time, never counters`() {
        val answer = splice.core.head.ProviderAnswer(403, 1_800_000_000_123L)
        val head = MeasuredHead(measured(emptyList()).copy(providerErrors = 9), answer = answer)
        val observed = HeadStatus.json(head, "kimi-oauth").getValue("last_provider_answer").jsonObject
        assertEquals(setOf("status", "observed_at_epoch_ms", "accepted"), observed.keys)
        assertEquals("false", observed.getValue("accepted").jsonPrimitive.content)
        assertEquals("403", observed.getValue("status").jsonPrimitive.content)
        assertEquals("1800000000123", observed.getValue("observed_at_epoch_ms").jsonPrimitive.content)
        val unknown = HeadStatus.json(MeasuredHead(measured(emptyList())), "kimi-oauth")
        assertEquals(kotlinx.serialization.json.JsonNull, unknown["last_provider_answer"])
    }

    private fun gateOf(health: HeadHealth): JsonObject =
        HeadStatus.json(MeasuredHead(health), "chatgpt-oauth")["gate"]!!.jsonObject

    private fun measured(live: List<GateSlot>) = HeadHealth(
        ok = true,
        running = true,
        port = 3099,
        version = "test",
        gate = GateHealth(
            inflight = live.size,
            queued = 1,
            limit = 4,
            acquired = 9,
            released = 7,
            waited = 3,
            avgWaitMs = 120,
            live = live,
            streamIdleMs = 90_000,
        ),
    )

    @Test
    fun `the gate carries what the head measured, one live row per slot in the order held`() {
        val streaming = GateSlot("b2e4d8f1 gpt-5.6-sol", false, GatePhase.STREAMING, ageMs = 5_000, idleMs = 40)
        val connecting = GateSlot("gpt-5.6-sol", compact = true, GatePhase.CONNECT, ageMs = 900, idleMs = 900)
        val expected = listOf(
            mapOf("label" to "b2e4d8f1 gpt-5.6-sol", "compact" to "false", "phase" to "streaming") +
                mapOf("age_ms" to "5000", "idle_ms" to "40"),
            mapOf("label" to "gpt-5.6-sol", "compact" to "true", "phase" to "connect") +
                mapOf("age_ms" to "900", "idle_ms" to "900"),
        )
        val gate = gateOf(measured(listOf(streaming, connecting)))

        val counts = listOf("acquired", "released", "waited", "avg_wait_ms", "stream_idle_ms")
        assertEquals(listOf(9L, 7L, 3L, 120L, 90_000L), counts.map { gate[it]!!.jsonPrimitive.content.toLong() })

        val live = gate["live"]!!.jsonArray.map { row -> row.jsonObject.mapValues { it.value.jsonPrimitive.content } }
        assertEquals(expected, live)
    }

    @Test
    fun `a head with nothing in flight has an empty live list and its counts`() {
        val gate = gateOf(measured(emptyList()))
        assertEquals(0, gate["live"]!!.jsonArray.size)
        assertEquals("7", gate["released"]!!.jsonPrimitive.content)
    }
}
