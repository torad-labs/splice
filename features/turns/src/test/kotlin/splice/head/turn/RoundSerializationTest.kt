// NEW: V4-457 — byte identity and current-thread allocation of one synthetic large turn.
package splice.head.turn

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.ClientAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.head.HeadHealthCounters
import splice.head.admission.admittedSlot
import splice.head.headDeps
import splice.head.round.RoundStrategy
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.UsagePayloadBuilder
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderIdentity
import splice.upstream.ProviderTuning
import splice.upstream.StreamTranslator
import splice.upstream.TurnSignals
import splice.upstream.retry.InflightGate
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class RoundSerializationTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `round wire keeps non ASCII escaping and nested tool blocks byte identical`() = runTest {
        val golden = """{"model":"synthetic","messages":[{"role":"assistant","content":[{"type":"text","text":"café 🐉\nquote \" and slash \\"},{"type":"tool_use","id":"synthetic-id","name":"synthetic_tool","input":{"nested":[{"text":"🧪","tab":"\t"}],"number":1.0,"null":null}}]}]}"""
        val actual = SerializationRig(tmp).turn(Json.parseToJsonElement(golden).jsonObject)
        assertArrayEquals(golden.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `assembling a drive does not serialize a request that has not been posted`() = runTest {
        val drive = SerializationRig(tmp).assemble(buildJsonObject { })
        try {
            assertNull(drive.perf.snapshot().counters[PerfKeys.UPSTREAM_REQ_BYTES])
        } finally {
            drive.slot.release()
        }
    }

    @Test
    fun `one synthetic 1 point 4 MB turn stays within its serialization allocation budget`(
        reporter: TestReporter,
    ) = runTest {
        val request = largeRequest()
        val rig = SerializationRig(tmp)
        val expected = request.toString()
        assertTrue(expected.length in 1_400_000..1_500_000)
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: error("JVM allocation counter is required")
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        repeat(10) { rig.turn(request) }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val actual = rig.turn(request)
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(thread == Thread.currentThread().threadId(), "the measured turn must stay on this thread")
        reporter.publishEntry("synthetic_turn_allocated_bytes", allocated.toString())
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
        // One retained wire string plus streaming digest and bounded encoder scratch, not recursive tree copies.
        assertTrue(allocated < 12 * 1024 * 1024, "synthetic_turn_allocated_bytes=$allocated; budget=12582912")
    }

    private fun largeRequest(): JsonObject = buildJsonObject {
        put("model", "synthetic")
        val text = "x".repeat(22_000)
        val block = buildJsonObject {
            put("type", "text")
            put("text", text)
            put("metadata", buildJsonObject { put("synthetic", true) })
        }
        val content = JsonArray(listOf(block))
        val messages = List(64) {
            buildJsonObject {
                put("role", "user")
                put("content", content)
            }
        }
        put("messages", JsonArray(messages))
    }
}

private class SerializationRig(tmp: Path) {
    private val provider = SerializationProvider()
    private val gate = InflightGate({ 1 })
    private val factory = TurnDriveFactory(provider, headDeps(tmp), HeadHealthCounters())
    private val terminal = CollectingTerminal("synthetic", UsagePayloadBuilder { buildJsonObject { } })
    private val meta = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.OFF,
        stream = true,
        originalModel = "synthetic",
        upstreamModel = "synthetic",
        clientMaxTokens = 100,
        effort = "high",
        summary = null,
        budgetTokens = null,
    )

    suspend fun assemble(body: JsonObject): TurnDrive = factory.assembleDrive(
        TurnInputs(
            built = BuiltTurn(body, meta),
            slot = gate.admittedSlot(),
            t0 = 0L,
            perf = TurnPerf(),
            markHandedOff = {},
            trace = null,
        ),
        terminal,
        ClientChannel(ImmediateSseWriter(writeRaw = {}, flushRaw = {}), Mutex(), AtomicBoolean(false)),
    )

    suspend fun turn(body: JsonObject): String {
        val drive = assemble(body)
        var posted = ""
        try {
            MessagesHash.of(body)
            val success = TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage())
            RoundStrategy(
                key = provider.key,
                log = {},
                emitter = terminal,
                signals = drive.signals,
                postRoundToSink = { _, _ -> error("the direct round must not fold") },
                postRound = { wire ->
                    posted = wire
                    success
                },
                finish = {},
            ).run(drive.requestBody, null, null, drive.perf)
        } finally {
            drive.slot.release()
        }
        return posted
    }
}

private class SerializationProvider :
    Provider,
    ProviderIdentity by ProviderTuning(
        key = "synthetic",
        label = "synthetic",
        catalog = ModelCatalog(
            discoveryPrefix = "synthetic-",
            models = listOf(ModelEntry("synthetic", contextWindow = 2_000_000)),
            defaultContextWindow = 2_000_000,
        ),
        pinnedModel = "synthetic",
        auth = ClientAuthProvider("synthetic"),
        baseUrl = "http://synthetic.invalid",
        watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
    ) {
    override val upstreamUrl = "http://synthetic.invalid"
    override val showReasoning = ReasoningDisplay.OFF
    override val replayReasoning = false

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
        error("the fixture starts with an already-built request")

    override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        error("the fixture must never start an upstream transport")
}
