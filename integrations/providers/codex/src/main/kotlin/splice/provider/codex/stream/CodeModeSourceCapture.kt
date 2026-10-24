// NEW: item completion seals executable source; response completion independently settles continuity and usage.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeValidation
import splice.provider.codex.CodexCodeModeWire
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.sse.CustomToolSource

internal class CodeModeSourceCapture(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    admission: CodeModeStreamAdmission,
    private var recovery: CodeModeRecoveryHistory? = null,
) {
    // One use only: the driver's closure reaches the raw request, context and client call.
    private var admission: CodeModeStreamAdmission? = admission

    @Volatile var disposed: Boolean = false
        private set

    val source: CodeModeSourceBuffer = CodeModeSourceBuffer(
        CodeModeSourceCommit { text ->
            if (!registry.source.append(checkNotNull(record), text)) dispose()
        },
    )
    val ready = CompletableDeferred<CodeModeRecord?>()
    var record: CodeModeRecord? = null
        private set
    private var startedCall: GatewayCustomCall? = null
    private var completedCall: GatewayCustomCall? = null
    private var recoveredSource: CodeModeRecoveryHistory.Source? = null

    @Volatile var itemComplete: Boolean = false
        private set

    val unfinished: Boolean get() = !itemComplete && record != null
    val unavailable: Boolean get() = !itemComplete && record?.terminal() == true

    fun rejects(outcome: TurnOutcome): Boolean = !itemComplete && uncertified(outcome) != null

    /** Published before waking a source cursor, without taking its round's lifecycle monitor. */
    private fun dispose() {
        disposed = true
        source.dispose(SOURCE_DISPOSED)
    }

    fun observe(event: CustomToolSource) {
        when (event) {
            is CustomToolSource.Started -> begin(event.call)
            is CustomToolSource.Delta -> delta(event)
            is CustomToolSource.Completed -> complete(event.call, event.snapshot)
            CustomToolSource.Terminal -> Unit // the live round reads it, before this capture is asked
        }
    }

    private fun complete(call: GatewayCustomCall, snapshot: TurnOutcome.Success? = null) {
        if (record == null) begin(call.copy(input = ""))
        check(completedCall == null) { "exec item completed more than once" }
        checkIdentity(call, checkNotNull(startedCall))
        check(call.input.startsWith(source.text)) { "completed exec source changed its dispatched prefix" }
        CodexCodeModeValidation(config).outer(call)?.let { error(it) }
        val continuity = snapshot?.let { recoveredSource?.finish(it, wire) ?: wire.continuity(it) }
        if (!registry.source.seal(checkNotNull(record), call, continuity)) {
            dispose()
            return
        }
        completedCall = call
        itemComplete = true
        source.complete(call.input)
    }

    private fun begin(call: GatewayCustomCall) {
        check(record == null) { "code mode accepts one outer custom call per round" }
        val admit = checkNotNull(admission) { "code-mode source admission was already used" }
        admission = null
        val admitted = admit.admit(call)
        record = admitted
        recoveredSource = recovery?.source(admitted)
        recovery = null
        startedCall = call
        if (call.input.isNotEmpty()) {
            checkSource(call.input)
            source.publish(call.input)
        }
        ready.complete(admitted)
    }

    private fun delta(event: CustomToolSource.Delta) {
        val current = checkNotNull(record) { "exec source has no admitted item" }
        check(event.callId == current.origin.outerCallId) { "streamed exec identity changed" }
        check(completedCall == null) { "exec source continued after item completion" }
        val appended = source.text + event.text
        checkSource(appended)
        if (!source.publish(appended)) {
            config.log("[code-mode] a streamed exec delta arrived after its source failed; dropped")
        }
    }

    private fun checkIdentity(call: GatewayCustomCall, expected: GatewayCustomCall) {
        check(call.callId == expected.callId && call.name == expected.name) { "streamed exec identity changed" }
        expected.raw["id"]?.let { item ->
            check(call.raw["id"] == item) { "streamed exec item identity changed" }
        }
    }

    private fun checkSource(text: String) {
        require(text.length <= config.bounds.maxSourceChars && CodeModeLimits.fitsText(text)) {
            "exec source exceeds the size limit"
        }
    }

    /** Why the terminal [outcome] does not certify the admitted source, with no source text and no ids, or null when
     *  no source was admitted or the terminal is a complete success carrying exactly one exec call. */
    fun uncertified(outcome: TurnOutcome): String? = if (record == null) {
        null
    } else {
        when (outcome) {
            is TurnOutcome.Failure ->
                "failure cause=${outcome.cause} permanent=${outcome.traits.permanent} " +
                    "provider=${outcome.traits.providerReported}"
            is TurnOutcome.ClientAbandoned -> "client abandoned"
            is TurnOutcome.Success -> when {
                outcome.incomplete -> "incomplete ${outcome.shape.outputShape}"
                outcome.handoffs.customCalls.size != 1 ->
                    "calls=${outcome.handoffs.customCalls.size} ${outcome.shape.outputShape}"
                else -> null
            }
        }
    }

    fun finish(outcome: TurnOutcome) {
        recovery = null
        val current = record ?: return
        val why = uncertified(outcome)
        val success = terminalSuccess(outcome, why)
        if (success == null) {
            unfinished(outcome, current, why)
            return
        }
        val call = success.handoffs.customCalls.single()
        val started = checkNotNull(startedCall)
        checkIdentity(call, started)
        checkCompleted(call)
        check(call.input.startsWith(source.text)) { "terminal exec source changed its dispatched prefix" }
        checkSource(call.input)
        if (!itemComplete) complete(call, success)
        if (disposed) return
        val continuity = recoveredSource?.finish(success, wire) ?: wire.continuity(success)
        if (!registry.source.finish(current, call, continuity, success.usage)) {
            dispose()
        }
    }

    private fun terminalSuccess(outcome: TurnOutcome, why: String?): TurnOutcome.Success? {
        val success = outcome as? TurnOutcome.Success ?: return null
        if (itemComplete && !success.incomplete) {
            check(success.handoffs.customCalls.size == 1) { "terminal exec item count changed" }
        }
        return success.takeIf { why == null }
    }

    private fun checkCompleted(call: GatewayCustomCall) {
        completedCall?.let { captured ->
            checkIdentity(call, captured)
            check(captured.input == call.input) { "terminal exec source changed its completed item" }
        }
    }

    private fun unfinished(outcome: TurnOutcome, current: CodeModeRecord, why: String?) {
        config.log("[code-mode] upstream exec source did not complete: $why")
        source.fail(SOURCE_INCOMPLETE)
        if (!itemComplete) {
            registry.lose(current, SOURCE_INCOMPLETE)
            return
        }
        val usage = when (outcome) {
            is TurnOutcome.Success -> outcome.usage
            is TurnOutcome.Failure -> (outcome.partial?.usage ?: noRequestUsage) + outcome.salvagedUsage
            is TurnOutcome.ClientAbandoned -> outcome.salvagedUsage
        }
        registry.source.interrupted(current, usage.takeIf { it.reported.isNotEmpty() })
    }

    fun interrupted(detail: String = SOURCE_INCOMPLETE, cancellation: CancellationException? = null) {
        recovery = null
        val current = record ?: return
        if (!itemComplete) {
            if (!current.terminal()) registry.lose(current, detail, cancellation)
            return
        }
        try {
            registry.source.interrupted(current)
        } catch (error: CodeModePersistenceException) {
            if (cancellation == null) throw error
            cancellation.addSuppressed(error)
        }
    }
}

private const val SOURCE_INCOMPLETE = "upstream exec source did not complete; source was not rerun"
