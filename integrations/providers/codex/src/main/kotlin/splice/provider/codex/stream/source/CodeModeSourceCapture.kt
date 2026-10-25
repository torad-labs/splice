package splice.provider.codex.stream.source

import kotlinx.coroutines.CancellationException
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.stream.CodeModeRecoveryHistory
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.provider.codex.stream.batch.CodeModeBatchAdmissions
import splice.provider.codex.stream.batch.CodeModeCompletedSources
import splice.upstream.sse.CustomToolSource

/** One response owns its reader and bill; each executable item owns an independent durable source cursor. */
internal class CodeModeSourceCapture(
    config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    wire: CodexCodeModeWire,
    admission: CodeModeStreamAdmission,
    recovery: CodeModeRecoveryHistory? = null,
) {
    private val admissions = CodeModeBatchAdmissions(registry, admission)
    private val sealed = CodeModeCompletedSources()
    private val first = CodeModeItemSourceCapture(config, registry, wire, admissions, recovery, admissions)
    private val items = java.util.concurrent.ConcurrentHashMap<String, CodeModeItemSourceCapture>()
    private val factory = CodeModeItemCaptures(config, registry, wire, admissions)
    val source get() = first.source
    val ready get() = first.ready
    val record get() = sealed.owner ?: first.record

    @Volatile private var executingId: String? = null
    private val execution get() = executingId?.let(items::get) ?: first
    val executingRecord get() = execution.record
    val disposed get() = execution.disposed
    val itemComplete get() = execution.itemComplete
    val unfinished get() = execution.unfinished
    val unavailable get() = execution.unavailable

    fun executing(record: CodeModeRecord) { executingId = record.origin.outerCallId }

    fun observe(event: CustomToolSource, witness: CodeModeDeliveredText? = null) {
        when (event) {
            is CustomToolSource.Started -> capture(event.call).observe(event, witness)
            is CustomToolSource.Completed -> {
                capture(
                    event.call,
                ).observe(event.copy(snapshot = CodeModeItemPrefix.of(event.snapshot, event.call)), witness)
                val record = checkNotNull(items[event.call.callId]?.record)
                sealed.add(record)
            }
            is CustomToolSource.Delta -> checkNotNull(items[event.callId]).observe(event, witness)
            is CustomToolSource.Continuity -> {
                checkNotNull(items[event.callId]).observe(single(event), witness)
                admissions.capture(event.snapshot)
            }
            CustomToolSource.Terminal -> Unit
        }
    }

    private fun capture(call: GatewayCustomCall): CodeModeItemSourceCapture = items.getOrPut(call.callId) {
        if (items.isEmpty()) first else factory.create()
    }

    private fun single(event: CustomToolSource.Continuity): CustomToolSource.Continuity = event.copy(
        snapshot = itemOutcome(event.snapshot, event.callId, true),
    )

    fun sourceFor(record: CodeModeRecord) =
        checkNotNull(items[record.origin.outerCallId]).source

    fun delivered(witness: CodeModeDeliveredText) {
        items.values.forEach { it.delivered(witness) }
    }

    suspend fun select(record: CodeModeRecord): CodeModeRecord? = sealed.select(record)

    suspend fun next(record: CodeModeRecord): CodeModeRecord? = sealed.next(record)

    fun rejects(outcome: TurnOutcome): Boolean = execution.unfinished && execution.rejects(forExecution(outcome))

    fun finish(outcome: TurnOutcome) {
        if (outcome is TurnOutcome.Success) {
            outcome.handoffs.customCalls.filter { items[it.callId]?.itemComplete != true }.forEach {
                observe(CustomToolSource.Completed(it, outcome))
            }
            admissions.capture(outcome)
        }
        items.forEach { (id, item) ->
            val bill = id == record?.origin?.outerCallId
            item.finish(CodeModeItemOutcomes.of(outcome, id, bill), billingOwner = bill)
        }
        admissions.release()
        sealed.end()
    }

    private fun forExecution(outcome: TurnOutcome): TurnOutcome =
        if (outcome is TurnOutcome.Success && execution.record != null) {
            itemOutcome(outcome, checkNotNull(execution.record).origin.outerCallId, true)
        } else {
            outcome
        }

    private fun itemOutcome(
        outcome: TurnOutcome.Success,
        id: String,
        bill: Boolean,
    ): TurnOutcome.Success = outcome.copy(
        usage = if (bill) outcome.usage else noRequestUsage,
        handoffs = outcome.handoffs.copy(customCalls = outcome.handoffs.customCalls.filter { it.callId == id }),
    )

    fun endAdmissions() {
        // Every added item may own a startup ticket, including an earlier unfinished item when another sealed first.
        items.values.filterNot { it.itemComplete }.forEach { item -> item.record?.let(registry.startup::release) }
        admissions.release()
        sealed.end()
    }

    fun interrupted(message: String? = null, cancellation: CancellationException? = null) {
        endAdmissions()
        items.values.forEach { item ->
            if (message == null) item.interrupted() else item.interrupted(message, cancellation)
        }
    }
}

/** Extra cursors retain no request/controller closure beyond the response owner's bounded admission. */
private class CodeModeItemCaptures(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val admission: CodeModeBatchAdmissions,
) {
    fun create(): CodeModeItemSourceCapture = CodeModeItemSourceCapture(
        config,
        registry,
        wire,
        admission,
        sealing = admission,
    )
}
