package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.RoundResult
import splice.upstream.codemode.NativeCodeModeLifecycle

/** Ordinary failed client results remain values through native execution and a fresh process's journal reload. */
internal class CodeModeGatewayToolOutputProbe(
    private val fixture: CodeModeGatewayProbeSupport,
    private val runtime: NativeCodeModeLifecycle,
) {
    suspend fun run(read: Boolean): JsonObject {
        val manager = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, fixture.location, log = fixture.log))
        val posted = ArrayList<JsonArray>()
        val callbacks = if (read) restoredCallbacks(manager).toMutableList() else ArrayList()
        val before = retained(manager)
        try {
            repeat(if (read) 1 else 2) { stage ->
                runStage(manager, posted, callbacks, !read && stage == 0)
            }
            return buildJsonObject {
                put("schema", 1); put("caseId", "runtime.failed-tool-output-${if (read) "read" else "write"}")
                put("nonce", fixture.nonce); put("pid", ProcessHandle.current().pid())
                put("scope", "real-native-runtime-through-production-gateway")
                put("restored", before); put("retained", retained(manager)); put("posted", JsonArray(posted))
                put("callbacks", JsonArray(callbacks))
            }
        } finally {
            manager.onProviderStop()
        }
    }

    private suspend fun runStage(
        manager: CodexCodeModeBridge,
        posted: MutableList<JsonArray>,
        callbacks: MutableList<JsonObject>,
        startsSource: Boolean,
    ) {
        val request = CodeModeGatewaySearchRequests(fixture).prepare(manager, false, callbacks)
        val sink = CodeModeGatewayBridgeSink()
        var started = false
        checkNotNull(request.roundInterceptor).intercept(JsonWire.string(request.requestBody), sink) { body ->
            posted += fixture.json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            val call = if (startsSource && !started) {
                started = true
                CodeModeGatewayCalls.exec(
                    "ordinary-output-${fixture.nonce}",
                    "try { text('ordinary-value='+await tools.hidden_tool({value:'${fixture.nonce}'})); } " +
                        "catch(e) { text('unexpected-rejection='+e.message); }",
                )
            } else {
                null
            }
            RoundResult.Outcome(
                TurnOutcome.Success(
                    false,
                    false,
                    noRequestUsage,
                    handoffs = RoundHandoffs(customCalls = listOfNotNull(call)),
                ),
            )
        }
        sink.callbackIds.forEachIndexed { index, id ->
            callbacks += buildJsonObject {
                put("id", id); put("name", sink.callbackNames[index])
                put("arguments", fixture.json.parseToJsonElement(sink.callbackInputs[index].toString()))
                put("result", output()); put("is_error", true)
            }
        }
    }

    private fun output(): JsonArray = buildJsonArray {
        listOf("permission denied ordinary-client-value-${fixture.nonce}", " ", "second-value").forEach { text ->
            add(buildJsonObject { put("type", "text"); put("text", text) })
        }
    }

    private fun restoredCallbacks(
        manager: CodexCodeModeBridge,
    ): List<JsonObject> = manager.registry.records.toList().flatMap { record ->
        record.issued.flatMap { it.calls }.filter { it.clientId in record.results }.map { pending ->
            buildJsonObject {
                put("id", pending.clientId); put("name", pending.name); put("arguments", pending.arguments)
                put("result", output()); put("is_error", true)
            }
        }
    }

    private fun retained(manager: CodexCodeModeBridge): JsonArray = JsonArray(
        manager.registry.records.toList().flatMap { record ->
            record.results.values.map { result ->
                buildJsonObject {
                    put("id", result.id); put("is_error", result.isError); put("disposition", result.disposition.name)
                    put(
                        "origin",
                        result.origin.name,
                    ); put("output", result.output); put("payload", result.payload ?: JsonNull)
                    put("value", splice.upstream.codemode.CodeModeToolOutputs.value(result))
                }
            }
        },
    )
}
