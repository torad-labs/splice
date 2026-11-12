package splice.app.control.codemode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import splice.core.index.WireBlockIndex
import splice.core.turn.GatewayCustomCall
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.RoundResult
import splice.upstream.sse.WireSink
import java.util.UUID

/** Scripted model replies still execute through the production gateway and packaged native guest. */
internal class InterruptNativeConversation(
    private val bridge: CodexCodeModeBridge,
    private val session: String,
    private val conversation: String,
) {
    private var history = JsonArray(emptyList())

    suspend fun execute(vararg sources: String): List<String> = invoke(sources.map { "exec" to it })

    suspend fun wait(cell: String): String = invoke(
        listOf("wait" to JsonWire.string(buildJsonObject { put("cell_id", cell); put("yield_time_ms", 1) })),
    ).single()

    fun cell(output: String): String = output.lineSequence()
        .first { it.startsWith("Script running with cell ID ") }
        .removePrefix("Script running with cell ID ").also { require(it.isNotBlank()) }

    private suspend fun invoke(actions: List<Pair<String, String>>): List<String> {
        val calls = ArrayDeque(actions.map { (name, source) -> call(name, source) })
        val ids = calls.map { it.callId }
        val request = buildJsonObject {
            put(
                "input",
                JsonArray(
                    history + buildJsonObject { put("role", "user"); put("content", UUID.randomUUID().toString()) },
                ),
            )
        }
        val turn = CodexCodeModeBridge.Turn(session, conversation, "fixture-model", emptySet())
        val result = bridge.interceptor(
            turn,
            disableParallel = false,
        ).intercept(JsonWire.string(request), QuietSink()) { body ->
            history = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            RoundResult.Outcome(
                TurnOutcome.Success(
                    false,
                    false,
                    noRequestUsage,
                    handoffs = RoundHandoffs(customCalls = listOfNotNull(calls.removeFirstOrNull())),
                ),
            )
        }
        assertTrue(result is RoundResult.Outcome && result.outcome is TurnOutcome.Success, result.toString())
        return ids.map { id -> output(id) }
    }

    private fun output(id: String): String {
        val item = history.filterIsInstance<JsonObject>().last {
            JsonScalars.str(it, "call_id") == id && JsonScalars.str(it, "type") in
                setOf("custom_tool_call_output", "function_call_output")
        }
        val value = item.getValue("output")
        return JsonScalars.str(value) ?: (value as JsonArray).filterIsInstance<JsonObject>()
            .mapNotNull { JsonScalars.str(it, "text") }.joinToString("\n")
    }

    private fun call(name: String, source: String): GatewayCustomCall {
        val id = UUID.randomUUID().toString()
        return GatewayCustomCall(
            id,
            name,
            source,
            buildJsonObject {
                put("type", if (name == "exec") "custom_tool_call" else "function_call")
                put("call_id", id); put("name", name); put(if (name == "exec") "input" else "arguments", source)
            },
        )
    }

    private class QuietSink : WireSink {
        override suspend fun openText(): WireBlockIndex = WireBlockIndex(0)
        override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(0)
        override suspend fun openTool(id: String, name: String): WireBlockIndex = error("No client tools are declared")
        override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
        override suspend fun closeBlock(index: WireBlockIndex) = Unit
        override suspend fun closeAll() = Unit
        override suspend fun addTextBlock(text: String) = Unit
        override suspend fun addRedactedThinking(data: String) = Unit
    }
}
