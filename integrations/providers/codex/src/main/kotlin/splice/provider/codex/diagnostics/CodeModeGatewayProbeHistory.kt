package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.history.CodeModeUpstreamHistory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

internal class CodeModeGatewayProbeHistory(private val fixture: CodeModeGatewayProbeSupport) {
    private val history = CodeModeUpstreamHistory(fixture.json, fixture.location, fixture.log)

    fun append(): JsonObject {
        seed()
        return observation()
    }

    fun restart(phase: String): JsonObject {
        require(phase != "single") { "restart requires separate write and read JVM invocations" }
        val registry = registry()
        try {
            if (phase == "write") {
                seed()
                check(registry.add(fixture.record())) { "production registry refused fixture admission" }
                registry.save()
            }
            val records = registry.recordsFor(fixture.key)
            val snapshots = records.map {
                fixture.json.encodeToJsonElement(CodeModeRecordSnapshot.serializer(), it.snapshot())
            }
            val unknown = registry.resultOwners(fixture.key, setOf("one", "two")).unknown.sorted()
            return buildJsonObject {
                put("history", observation())
                put("records", JsonArray(snapshots))
                put("unknownCallbackIds", JsonArray(unknown.map { JsonPrimitive(it) }))
            }
        } finally {
            registry.timed.finish { }
        }
    }

    private fun seed() {
        val context = fixture.context(fixture.initial)
        history.sent(context, fixture.body(fixture.initial))
        history.settled(
            context,
            TurnOutcome.Success(false, false, noRequestUsage, handoffs = RoundHandoffs(nativeOutput = fixture.native)),
        )
    }

    private fun observation(): JsonObject = buildJsonObject {
        put("initialClient", JsonArray(fixture.initial))
        put("nativeResponse", JsonArray(fixture.native))
        put("nextClient", JsonArray(fixture.nextClient))
        put("nextModelInput", fixture.input(history.extend(fixture.context(fixture.nextClient), emptyList())))
    }

    private fun registry(): CodexCodeModeRegistry = CodexCodeModeRegistry(
        CodeModeBridgeConfig(
            runtimes = { error("history ownership probe must not start a model or runtime") },
            state = fixture.location,
            clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
            log = fixture.log,
        ),
        fixture.json,
        1.hours,
    )
}
