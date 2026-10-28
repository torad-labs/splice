package splice.provider.codex.diagnostics.current

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.diagnostics.CodeModeGatewayBridgeSink
import splice.provider.codex.diagnostics.CodeModeGatewayProbeSupport
import splice.provider.codex.diagnostics.CodeModeInterruptControl
import splice.upstream.BuiltTurn
import splice.upstream.RoundResult
import splice.upstream.codemode.NativeCodeModeLifecycle
import java.security.MessageDigest

// why: a source-attested native notification must arrive within this bounded diagnostic turn.
private const val INTERRUPTED_PROBE_READY_MS = 15_000L

/** Observes real client cancellation followed by a real new user turn in the same native conversation. */
internal class CodeModeGatewayInterruptedProbe(
    private val fixture: CodeModeGatewayProbeSupport,
    private val runtime: NativeCodeModeLifecycle,
    private val interruptControl: CodeModeInterruptControl,
) {
    suspend fun run(bytes: ByteArray, specification: JsonObject): JsonObject = coroutineScope {
        val manager = CodexCodeModeBridge(CodeModeBridgeConfig({ runtime }, fixture.location, log = fixture.log))
        val plan = CodeModeGatewayScenarioPlan(specification, fixture.json)
        val requests = CodeModeGatewayScenarioRequests(fixture, specification)
        val interruption = specification.getValue("interruption").jsonObject
        val post = CodeModeGatewayInterruptedPost(fixture, plan, interruption)
        val first = requests.prepare(manager, emptyList())
        try {
            val interrupted = async { intercept(first, post) }
            withTimeout(INTERRUPTED_PROBE_READY_MS) { post.held.await() }
            interrupted.cancelAndJoin()
            check(interrupted.isCancelled) { "The owned request did not actually cancel" }
            val controlled = interruptControl.interrupt(manager, "session-${fixture.nonce}", fixture.key)
            val prompt = checkNotNull(JsonScalars.str(interruption, "resumePrompt"))
            val resumed = requests.prepare(manager, emptyList(), prompt)
            val result = intercept(resumed, post)
            plan.checkComplete()
            JsonObject(report(bytes, interruption, post, result) + ("interruptControl" to controlled))
        } finally {
            manager.onProviderStop()
        }
    }

    private suspend fun intercept(request: BuiltTurn, post: CodeModeGatewayInterruptedPost): RoundResult =
        checkNotNull(request.roundInterceptor).intercept(
            JsonWire.string(request.requestBody),
            CodeModeGatewayBridgeSink(),
            post,
        )

    private fun report(
        bytes: ByteArray,
        interruption: JsonObject,
        post: CodeModeGatewayInterruptedPost,
        result: RoundResult,
    ): JsonObject = buildJsonObject {
        val outcome = (result as? RoundResult.Outcome)?.outcome
        check(outcome is TurnOutcome.Success) { "The resumed native conversation did not complete" }
        put("schema", 1); put("caseId", "runtime.current-scenario"); put("nonce", fixture.nonce)
        put("pid", ProcessHandle.current().pid()); put("scope", "real-native-runtime-through-production-gateway")
        put("inputSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        put("posted", JsonArray(post.posted)); put("callbacks", JsonArray(emptyList()))
        put("outcomes", JsonArray(listOf(JsonPrimitive("Cancelled"), JsonPrimitive("Success"))))
        put(
            "interruption",
            JsonObject(interruption + mapOf("cancelled" to JsonPrimitive(true), "resumed" to JsonPrimitive(true))),
        )
    }
}
