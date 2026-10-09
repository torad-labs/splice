// Provider silence is distinct from turn age and client keep-alives.
package splice.head.turn

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.ElapsedClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.admission.admittedSlot
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.wire.ClientChannel
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.LostClient
import splice.upstream.Ticker
import splice.upstream.retry.InflightGate
import java.util.concurrent.atomic.AtomicBoolean

class LiveTurnIdleReadingTest {
    private var now = 1_000L
    private val clock = ElapsedClock { now }
    private val turns = LiveTurns(clock = clock)

    // The gate's clock is deliberately different: live-turn readings own their time origin.
    private val gate = InflightGate({ 4 }, clock = ElapsedClock { -10_000L })
    private val registry = LiveTurnsByHead().apply { put("test", turns) }
    private val routes = LiveTurnsRoutes(
        TurnsHeadLookup { listOf(TurnsHead("test", NoCompaction)) },
        LiveTurnsSource { registry },
    )

    private object NoCompaction : HeadCompactSource {
        override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
    }

    private fun admit(): InflightGate.Slot = runBlocking {
        val slot = gate.admittedSlot()
        now += 200L // Preparation happened after the gate was acquired.
        turns.admitted(
            slot,
            TurnMeta(
                compact = false,
                showReasoning = ReasoningDisplay.TEXT,
                stream = true,
                originalModel = "model",
                upstreamModel = "model",
                clientMaxTokens = 100,
                effort = "high",
                summary = "detailed",
                budgetTokens = null,
            ),
            messagesHash = null,
        )
        slot
    }

    private fun reading(): JsonObject = Json.parseToJsonElement(routes.live("test").body)
        .jsonObject.getValue("turns").jsonArray.single().jsonObject

    @Test
    fun `before the first provider byte idle equals age and the route publishes it`() {
        val slot = admit()
        try {
            now += 400L
            slot.touch() // Upstream headers are not a body byte.
            now += 100L
            val row = reading()
            assertEquals(500L, row.getValue("age_ms").jsonPrimitive.long)
            assertEquals(500L, row.getValue("idle_ms").jsonPrimitive.long)
        } finally {
            slot.release()
        }
    }

    @Test
    fun `a turn that streamed then went silent accumulates idle while age keeps growing`() {
        val slot = admit()
        try {
            now += 1_000L
            slot.received()
            now += 300_001L
            val row = reading()
            assertEquals(301_001L, row.getValue("age_ms").jsonPrimitive.long)
            assertEquals(300_001L, row.getValue("idle_ms").jsonPrimitive.long)
            now += 100L
            assertEquals(300_101L, reading().getValue("idle_ms").jsonPrimitive.long)
        } finally {
            slot.release()
        }
    }

    @Test
    fun `a streaming turn resets idle without resetting its age`() {
        val slot = admit()
        try {
            repeat(10) { n ->
                now += 400L
                slot.received()
                now += 1L
                val row = reading()
                assertEquals(401L * (n + 1), row.getValue("age_ms").jsonPrimitive.long)
                assertEquals(1L, row.getValue("idle_ms").jsonPrimitive.long)
            }
        } finally {
            slot.release()
        }
    }

    @Test
    fun `provider activity updates only the turn owning that slot`() {
        val first = admit()
        val second = admit()
        try {
            now += 500L
            first.received()
            assertEquals(listOf(0L, 500L), turns.list().map { it.idleMs })
            assertEquals(listOf(700L, 500L), turns.list().map { it.ageMs })
        } finally {
            first.release()
            second.release()
        }
    }

    @Test
    fun `client keep-alives and ping frames do not reset provider silence`() = runBlocking<Unit> {
        val slot = admit()
        val written = mutableListOf<String>()
        val channel = ClientChannel(
            ImmediateSseWriter(writeRaw = { written += it }, flushRaw = {}),
            Mutex(),
            AtomicBoolean(false),
        )
        var ticks = 0
        val ticker = Ticker {
            if (ticks == 30) {
                false
            } else {
                ticks += 1
                now += 100L
                true
            }
        }
        try {
            channel.launchClientPinger(this, Job(), ticker, LostClient("test", {})) {
                channel.timedClientWrite("event: ping\n\n", TurnPerf(), clock)
            }.join()
            assertEquals(28, written.count { it.startsWith(": ping") })
            assertEquals(2, written.count { it.startsWith("event: ping") })
            val row = reading()
            assertEquals(3_000L, row.getValue("age_ms").jsonPrimitive.long)
            assertEquals(3_000L, row.getValue("idle_ms").jsonPrimitive.long)
        } finally {
            slot.release()
        }
    }
}
