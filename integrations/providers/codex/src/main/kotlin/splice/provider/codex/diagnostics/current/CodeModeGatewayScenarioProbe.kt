package splice.provider.codex.diagnostics.current

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRuntimes
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.diagnostics.CodeModeGatewayBridgeSink
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.provider.codex.diagnostics.CodeModeInterruptControl
import splice.upstream.RoundResult
import splice.upstream.codemode.NativeCodeModeLifecycle
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Model-free scenarios observe real gateway history and callbacks without replacing runtime classes. */
internal class CodeModeGatewayScenarioProbe(
    private val fixture: CodeModeGatewayProbeSupport,
    private val runtime: NativeCodeModeLifecycle,
    private val interruptControl: CodeModeInterruptControl? = null,
    private val availabilityRuntimes: CodeModeRuntimes? = null,
) {
    suspend fun run(directory: Path): JsonObject {
        val bytes = Files.readAllBytes(directory.resolve("scenario.json"))
        val specification = fixture.json.parseToJsonElement(bytes.decodeToString()).jsonObject
        if (specification.containsKey("interruption")) {
            val control = checkNotNull(interruptControl) {
                "Interruption qualification requires the actual authenticated application control route"
            }
            return CodeModeGatewayInterruptedProbe(fixture, runtime, control).run(bytes, specification)
        }
        return scenario(bytes, specification, directory)
    }

    private suspend fun scenario(bytes: ByteArray, specification: JsonObject, directory: Path): JsonObject {
        val plan = CodeModeGatewayScenarioPlan(specification, fixture.json)
        val manager = manager(specification)
        val requests = CodeModeGatewayScenarioAdapter(fixture, specification, directory)
        val trace = Trace()
        try {
            while (!plan.finished) {
                check(trace.outcomes.size < plan.stageLimit) { "Scenario exceeded its bound client stages" }
                val request = requests.prepare(manager, plan.callbacks)
                trace.declarations += request.requestBody
                val sink = CodeModeGatewayBridgeSink()
                val result = checkNotNull(
                    request.roundInterceptor,
                ).intercept(JsonWire.string(request.requestBody), sink) { body ->
                    val input = fixture.json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
                    trace.posted += input
                    RoundResult.Outcome(
                        TurnOutcome.Success(
                            false,
                            false,
                            noRequestUsage,
                            handoffs = RoundHandoffs(customCalls = plan.next(input)),
                        ),
                    )
                }
                trace.outcomes += when (result) {
                    is RoundResult.Outcome -> (result.outcome as? TurnOutcome.Failure)?.evidence?.message
                        ?: result.outcome::class.simpleName.orEmpty()
                    is RoundResult.Ended -> "Ended"
                }
                trace.clientText += sink.text.toString()
                plan.accept(sink)
            }
            plan.checkComplete()
            val report = report(bytes, plan.callbacks, trace)
            return requests.report(manager, report)
        } finally {
            manager.onProviderStop()
        }
    }

    private fun manager(specification: JsonObject): CodexCodeModeBridge {
        val runtimes = if (specification.containsKey("availability")) {
            CodeModeGatewayAvailability(fixture, specification).runtimes(availabilityRuntimes)
        } else {
            CodeModeRuntimes { runtime }
        }
        return CodexCodeModeBridge(CodeModeBridgeConfig(runtimes, fixture.location, log = fixture.log))
    }

    private fun report(
        bytes: ByteArray,
        callbacks: List<JsonObject>,
        trace: Trace,
    ): JsonObject = buildJsonObject {
        put("schema", 1); put("caseId", "runtime.current-scenario"); put("nonce", fixture.nonce)
        put("pid", ProcessHandle.current().pid()); put("scope", "real-native-runtime-through-production-gateway")
        put("inputSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        put("posted", JsonArray(trace.posted)); put("declarations", JsonArray(trace.declarations))
        put("callbacks", JsonArray(callbacks)); put("outcomes", JsonArray(trace.outcomes.map(::JsonPrimitive)))
        put("clientText", JsonArray(trace.clientText.map(::JsonPrimitive)))
    }

    private class Trace {
        val posted = ArrayList<JsonArray>()
        val declarations = ArrayList<JsonObject>()
        val outcomes = ArrayList<String>()
        val clientText = ArrayList<String>()
    }
}
