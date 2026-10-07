package splice.usage.statusline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.perf.PerfSessionTail
import splice.core.perf.PerfSessionTurn
import splice.core.turn.noRequestUsage
import splice.usage.perf.HeadSessionPerfSource

class SessionCostUnknownUsageTest {
    private val model = "synthetic-model"
    private val session = "synthetic-session"
    private val catalog = ModelCatalog(
        discoveryPrefix = "synthetic--",
        models = listOf(ModelEntry(model, contextWindow = 100_000, rates = ModelRates(2.0, 0.2, 10.0))),
        defaultContextWindow = 100_000,
        pinnedModel = model,
    )

    private fun cost(rows: List<Map<String, Long>>, cards: ModelCatalog? = catalog) = SessionCost(
        HeadSessionPerfSource { PerfSessionTail(rows.map { PerfSessionTurn(model, it) }, null) },
        cards,
    )

    @Test
    fun `a no-request refusal cannot turn an exact session price into a lower bound`() {
        val zero = mapOf(
            PerfKeys.IN_TOKENS to 0L,
            PerfKeys.OUT_TOKENS to 0L,
            PerfKeys.CACHED_TOKENS to 0L,
            PerfKeys.CACHE_WRITE_TOKENS to 0L,
            PerfKeys.ATTEMPTS to 1L,
        )
        val refusal = TurnBill.counters(noRequestUsage) + (PerfKeys.ATTEMPTS to 0L)
        val spend = cost(listOf(zero, refusal)).spendFor(session, model, null)!!
        assertFalse(spend.lowerBound)
        assertEquals(0.0, spend.usd)
    }

    @Test
    fun `a refusal-only session has exact zero without a seeded posted row`() {
        val spend = cost(listOf(TurnBill.counters(noRequestUsage))).spendFor(session, model, null)!!
        assertEquals(0.0, spend.usd)
        assertFalse(spend.lowerBound)
    }

    @Test
    fun `a refusal-only session needs no rate card`() {
        val spend = cost(listOf(TurnBill.counters(noRequestUsage)), cards = null)
            .spendFor(session, model, null)!!
        assertEquals(0.0, spend.usd)
        assertFalse(spend.lowerBound)
    }

    @Test
    fun `a no-card refusal cannot hide a posted unknown bill in the same session`() {
        val posted = mapOf(PerfKeys.TRANSPORT_ATTEMPT_STARTS to 1L, PerfKeys.ATTEMPTS to 0L)
        val spend = cost(listOf(TurnBill.counters(noRequestUsage), posted), cards = null)
            .spendFor(session, model, null)!!
        assertEquals(0.0, spend.usd)
        assertTrue(spend.lowerBound)
    }

    @Test
    fun `a websocket abort before any event stays unknown even with zero attempts`() {
        val known = mapOf("in_tokens" to 100L, "out_tokens" to 7L, "cached_tokens" to 0L, "cache_write_tokens" to 0L)
        val abort = mapOf(PerfKeys.ATTEMPTS to 0L, PerfKeys.TRANSPORT_ATTEMPT_STARTS to 1L)
        val spend = cost(listOf(known, abort)).spendFor(session, model, null)!!
        assertTrue(spend.lowerBound)
        assertEquals(0.00027, spend.usd, 1e-12)
    }

    @Test
    fun `unknown usage leaves known session spend as a lower bound`() {
        val known = mapOf("in_tokens" to 100L, "out_tokens" to 7L, "cached_tokens" to 0L, "cache_write_tokens" to 0L)
        val partial = mapOf("in_tokens" to 200L)
        val unknown = mapOf("attempts" to 1L)
        val spend = cost(listOf(known, partial, unknown)).spendFor(session, model, null)!!
        assertEquals(0.00027, spend.usd, 1e-12)
        assertTrue(spend.lowerBound)
        assertNull(cost(listOf(partial, unknown)).spendFor(session, model, null))
    }

    @Test
    fun `a posted wholly unreported turn makes a known session a lower bound`() {
        val known = mapOf("in_tokens" to 100L, "out_tokens" to 7L, "cached_tokens" to 0L, "cache_write_tokens" to 0L)
        val spend = cost(listOf(known, mapOf("transport_attempt_starts" to 1L))).spendFor(session, model, null)!!
        assertTrue(spend.lowerBound, "a posted request did not report how much it spent")
        assertEquals(0.00027, spend.usd, 1e-12)
    }
}
