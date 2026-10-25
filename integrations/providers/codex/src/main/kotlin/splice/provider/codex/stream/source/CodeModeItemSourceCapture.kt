// NEW: item completion seals executable source; response completion independently settles continuity and usage.
package splice.provider.codex.stream.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeValidation
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.stream.CodeModeRecoveryHistory
import splice.provider.codex.stream.CodeModeSourceBuffer
import splice.provider.codex.stream.CodeModeSourceCommit
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.provider.codex.stream.SOURCE_DISPOSED
import splice.upstream.sse.CustomToolSource

internal class CodeModeItemSourceCapture(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    admission: CodeModeStreamAdmission,
    private var recovery: CodeModeRecoveryHistory? = null,
    private val sealing: CodeModeItemSeal? = null,
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
    private val continuity = CodeModeSourceContinuity(wire)
    private val items = CodeModeSourceItems(config)
    private val payloads = CodeModeSourcePayloads(registry)

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

    fun observe(event: CustomToolSource, witness: CodeModeDeliveredText? = null) {
        when (event) {
            is CustomToolSource.Started -> begin(event.call)
            is CustomToolSource.Delta -> delta(event)
            is CustomToolSource.Completed -> complete(event.call, event.snapshot)
            is CustomToolSource.Continuity -> progress(event, witness)
            CustomToolSource.Terminal -> Unit // the live round reads it, before this capture is asked
        }
    }

    private fun progress(event: CustomToolSource.Continuity, witness: CodeModeDeliveredText?) {
        val call = checkNotNull(completedCall) { "exec continuity has no completed item" }
        check(event.callId == call.callId) { "streamed exec continuity identity changed" }
        val reported = event.snapshot.handoffs.customCalls.singleOrNull { it.callId == call.callId }
        checkNotNull(reported) { "exec continuity lost its completed item" }
        items.completed(reported, call)
        if (!hold(event.snapshot.handoffs.nativeAfterExec)) return
        val captured = continuity.capture(event.snapshot, recoveredSource, witness)
        if (!registry.source.updateContinuity(checkNotNull(record), call, captured)) dispose()
    }

    fun delivered(witness: CodeModeDeliveredText) {
        val call = completedCall ?: return
        val captured = continuity.delivered(witness, recoveredSource) ?: return
        if (!registry.source.updateContinuity(checkNotNull(record), call, captured, durable = true)) dispose()
    }

    private fun hold(items: List<JsonObject>?): Boolean {
        val held = payloads.retain(checkNotNull(record), items)
        if (!held) dispose()
        return held
    }

    private fun complete(call: GatewayCustomCall, snapshot: TurnOutcome.Success? = null) {
        val fresh = record == null
        if (fresh) begin(call.copy(input = ""))
        check(completedCall == null) { "exec item completed more than once" }
        items.identity(call, checkNotNull(startedCall))
        check(call.input.startsWith(source.text)) { "completed exec source changed its dispatched prefix" }
        CodexCodeModeValidation(config).outer(call)?.let { invalid ->
            // A source that breaks its size bounds is a client input problem: the same words append()
            // rejects with require(); identity and protocol problems stay internal IllegalStateExceptions.
            val oversized = call.input.length > config.bounds.maxSourceChars ||
                !splice.upstream.codemode.CodeModeLimits.fitsText(call.input)
            if (oversized) throw IllegalArgumentException(invalid) else error(invalid)
        }
        if (!hold(snapshot?.handoffs?.nativeAfterExec)) return
        val captured = snapshot?.let { continuity.capture(it, recoveredSource) }
        val current = checkNotNull(record)
        val sealed = sealing?.seal(current, call, captured, snapshot) ?: registry.source.seal(current, call, captured)
        if (!sealed) {
            // A decline on an item this response never streamed is an identity change, not disposal;
            // disposal only closes items the round itself began.
            check(!fresh) { "streamed exec identity changed" }
            dispose()
            return
        }
        continuity.sealed(snapshot)
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
            items.source(call.input)
            source.publish(call.input)
        }
        ready.complete(admitted)
    }

    private fun delta(event: CustomToolSource.Delta) {
        val current = checkNotNull(record) { "exec source has no admitted item" }
        check(event.callId == current.origin.outerCallId) { "streamed exec identity changed" }
        check(completedCall == null) { "exec source continued after item completion" }
        val appended = source.text + event.text
        items.source(appended)
        if (!source.publish(appended)) {
            config.log("[code-mode] a streamed exec delta arrived after its source failed; dropped")
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

    fun finish(outcome: TurnOutcome, billingOwner: Boolean = true) {
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
        items.identity(call, started)
        completedCall?.let { items.completed(call, it) }
        check(call.input.startsWith(source.text)) { "terminal exec source changed its dispatched prefix" }
        items.source(call.input)
        if (!itemComplete) complete(call, success)
        if (disposed || !hold(success.handoffs.nativeAfterExec)) return
        val captured = continuity.capture(success, recoveredSource)
        if (!registry.source.finish(current, completedCall ?: call, captured, success.usage, billingOwner)) {
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

    private fun unfinished(outcome: TurnOutcome, current: CodeModeRecord, why: String?) {
        config.log("[code-mode] upstream exec source did not complete: $why")
        source.fail(SOURCE_INCOMPLETE)
        if (!itemComplete) {
            registry.startup.release(current)
            registry.lose(current, SOURCE_INCOMPLETE)
            return
        }
        val partial = (outcome as? TurnOutcome.Failure)?.partial
        if (!hold(partial?.nativeAfterExec)) return
        continuity.partial(partial, recoveredSource)?.let { captured ->
            registry.source.updateContinuity(current, checkNotNull(completedCall), captured)
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
            registry.startup.release(current)
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
