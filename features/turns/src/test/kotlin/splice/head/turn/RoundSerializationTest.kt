// The request body a round posts upstream is byte-identical to the JSON the client sent.
package splice.head.turn

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.ClientAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.head.HeadHealthCounters
import splice.head.admission.admittedSlot
import splice.head.headDeps
import splice.head.round.RoundRunners
import splice.head.round.RoundStrategy
import splice.head.wire.ClientChannel
import splice.head.wire.CollectingTerminal
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.UsagePayloadBuilder
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderIdentity
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.RetryBackoff
import splice.upstream.RoundResult
import splice.upstream.StreamTranslator
import splice.upstream.TurnSignals
import splice.upstream.retry.InflightGate
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class RoundSerializationTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `round wire keeps non ASCII escaping and nested tool blocks byte identical`() = runTest {
        val golden =
            """{"model":"synthetic","messages":[{"role":"assistant","content":[{"type":"text",""" +
                """"text":"café 🐉\nquote \" and slash \\"}""" +
                """,{"type":"tool_use","id":"synthetic-id","name":"synthetic_tool",""" +
                """"input":{"nested":[{"text":"🧪","tab":"\t"}],"number":1.0,"null":null}}]}]}"""
        val actual = SerializationRig(tmp).turn(Json.parseToJsonElement(golden).jsonObject)
        assertArrayEquals(golden.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }
}

private class SerializationRig(tmp: Path) {
    private val provider = SerializationProvider()
    private val gate = InflightGate({ 1 })
    private val factory = TurnDriveFactory(provider, headDeps(tmp), HeadHealthCounters())
    private val terminal = CollectingTerminal("synthetic", UsagePayloadBuilder { buildJsonObject { } })
    private val meta = TurnMeta(
        compact = false,
        reasoning = TurnReasoning(
            showReasoning = ReasoningDisplay.OFF,
            effort = "high",
            summary = null,
            budgetTokens = null,
        ),
        route = TurnRoute(
            stream = true,
            originalModel = "synthetic",
            upstreamModel = "synthetic",
            clientMaxTokens = 100,
        ),
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
                emitter = terminal,
                runners = RoundRunners(
                    backoff = RetryBackoff { _, _ -> },
                    key = provider.key,
                    log = {},
                    signals = drive.signals,
                    finish = {},
                ),
                postRoundToSink = { _, _ -> error("the direct round must not fold") },
                postRound = { wire ->
                    posted = wire.text
                    RoundResult.Outcome(success)
                },
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
        name = ProviderName(key = "synthetic", label = "synthetic"),
        catalog = ModelCatalog(
            discoveryPrefix = "synthetic-",
            models = listOf(ModelEntry("synthetic", contextWindow = 2_000_000)),
            defaultContextWindow = 2_000_000,
        ),
        pinnedModel = "synthetic",
        auth = ClientAuthProvider("synthetic"),
        locations = ProviderLocations(baseUrl = "http://synthetic.invalid"),
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
