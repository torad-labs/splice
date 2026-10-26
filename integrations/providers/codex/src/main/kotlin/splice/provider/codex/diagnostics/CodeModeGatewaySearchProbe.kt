package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** Search results execute through the real native bridge, then a changed catalog serves the next exec. */
internal class CodeModeGatewaySearchProbe(
    private val fixture: CodeModeGatewayProbeSupport,
    private val runtime: NativeCodeModeLifecycle,
) {
    suspend fun run(): JsonObject {
        val manager = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, fixture.location, log = fixture.log))
        val requests = CodeModeGatewaySearchRequests(fixture)
        val plan = CodeModeGatewaySearchPlan(fixture.nonce)
        val posted = ArrayList<JsonArray>()
        val callbacks = ArrayList<JsonObject>()
        val declarations = ArrayList<JsonObject>()
        val outcomes = ArrayList<String>()
        try {
            for (stage in 0..SEARCH_RESULT_STAGES) {
                val request = requests.prepare(manager, stage > 0, callbacks)
                declarations += request.requestBody
                val sink = CodeModeGatewayBridgeSink()
                val result = checkNotNull(
                    request.roundInterceptor,
                ).intercept(JsonWire.string(request.requestBody), sink) { body ->
                    val input = fixture.json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
                    posted += input
                    val call = plan.next()
                    RoundResult.Outcome(
                        TurnOutcome.Success(
                            false,
                            false,
                            noRequestUsage,
                            handoffs = RoundHandoffs(customCalls = listOfNotNull(call)),
                        ),
                    )
                }
                outcomes += when (result) {
                    is RoundResult.Outcome -> (result.outcome as? TurnOutcome.Failure)?.message
                        ?: result.outcome::class.simpleName.orEmpty()
                    is RoundResult.Ended -> "Ended"
                }
                callbacks += callbackRecords(sink, stage)
            }
            return buildJsonObject {
                put("schema", 1); put("caseId", "runtime.deferred-search"); put("nonce", fixture.nonce)
                put("pid", ProcessHandle.current().pid())
                put("scope", "real-native-runtime-through-production-gateway")
                put("posted", JsonArray(posted)); put("callbacks", JsonArray(callbacks))
                put("declarations", JsonArray(declarations))
                put("outcomes", JsonArray(outcomes.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
        } finally {
            manager.onProviderStop()
        }
    }

    private fun callbackRecords(sink: CodeModeGatewayBridgeSink, stage: Int): List<JsonObject> =
        sink.callbackIds.mapIndexed { index, id ->
            buildJsonObject {
                put("id", id)
                put("name", sink.callbackNames[index])
                put("arguments", fixture.json.parseToJsonElement(sink.callbackInputs[index].toString()))
                put("result", "client-result-$stage-${fixture.nonce}")
            }
        }
}

// why: the last stage index. Stage 0 posts the original catalog; stages 1 and 2 post the changed catalog with
// the results of every client tool call handed out so far. The port chose the count.
private const val SEARCH_RESULT_STAGES = 2
