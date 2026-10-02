// NEW: source observations are durable before execution; only the response terminal certifies EOF.
package splice.provider.codex.stream

import kotlinx.coroutines.CompletableDeferred
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.sse.CustomToolSource

internal class CodeModeSourceCapture(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    admission: CodeModeStreamAdmission,
) {
    // One use only: the driver's closure reaches the raw request, context and client call.
    private var admission: CodeModeStreamAdmission? = admission
    val source = CodeModeSourceBuffer()
    val ready = CompletableDeferred<CodeModeRecord?>()
    var record: CodeModeRecord? = null
        private set
    private var startedCall: GatewayCustomCall? = null
    private var completedCall: GatewayCustomCall? = null

    fun observe(event: CustomToolSource) {
        when (event) {
            is CustomToolSource.Started -> begin(event.call)
            is CustomToolSource.Delta -> delta(event)
            is CustomToolSource.Completed -> {
                if (record == null) begin(event.call.copy(input = ""))
                val current = checkNotNull(record)
                checkIdentity(event.call, checkNotNull(startedCall))
                check(event.call.input.startsWith(current.source)) {
                    "completed exec source changed its dispatched prefix"
                }
                checkSource(event.call.input)
                completedCall = event.call
            }
        }
    }

    private fun begin(call: GatewayCustomCall) {
        check(record == null) { "code mode accepts one outer custom call per round" }
        val admit = checkNotNull(admission) { "code-mode source admission was already used" }
        admission = null
        val admitted = admit.admit(call)
        record = admitted
        startedCall = call
        if (call.input.isNotEmpty()) {
            checkSource(call.input)
            registry.source.append(admitted, call.input)
            source.publish(call.input)
        }
        ready.complete(admitted)
    }

    private fun delta(event: CustomToolSource.Delta) {
        val current = checkNotNull(record) { "exec source has no admitted item" }
        check(event.callId == current.outerCallId) { "streamed exec identity changed" }
        check(completedCall == null) { "exec source continued after item completion" }
        val appended = current.source + event.text
        checkSource(appended)
        registry.source.append(current, appended)
        source.publish(appended)
    }

    private fun checkIdentity(call: GatewayCustomCall, expected: GatewayCustomCall) {
        check(call.callId == expected.callId && call.name == expected.name) { "streamed exec identity changed" }
        expected.raw["id"]?.let { item ->
            check(call.raw["id"] == item) { "streamed exec item identity changed" }
        }
    }

    private fun checkSource(text: String) {
        require(text.length <= config.maxSourceChars && CodeModeLimits.fitsText(text)) {
            "exec source exceeds the size limit"
        }
    }

    fun finish(outcome: TurnOutcome) {
        val current = record ?: return
        val success = outcome as? TurnOutcome.Success
        val call = success?.customCalls?.singleOrNull()
        if (call == null || success.incomplete) {
            source.fail("upstream exec source did not complete; source was not rerun")
            registry.lose(current, "upstream exec source did not complete; source was not rerun")
            return
        }
        val started = checkNotNull(startedCall)
        checkIdentity(call, started)
        completedCall?.let { captured ->
            checkIdentity(call, captured)
            check(captured.input == call.input) { "terminal exec source changed its completed item" }
        }
        check(call.input.startsWith(current.source)) { "terminal exec source changed its dispatched prefix" }
        checkSource(call.input)
        registry.source.finish(current, call, wire.continuity(success), success.usage)
        source.complete(call.input)
    }
}
