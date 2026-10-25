package splice.provider.codex.stream.batch

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CodeModeAdvanceRequest
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeMachine
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.history.CodeModeUpstreamHistory
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeRuntimeStarter
import splice.provider.codex.stream.CodeModeStreams

/** Dispatch proof, runtime adoption and the first foreground observation share one selected item identity. */
internal class CodeModeRuntimeDispatch(
    private val starter: CodeModeRuntimeStarter,
    private val registry: CodexCodeModeRegistry,
    private val machine: CodexCodeModeMachine,
    private val streams: CodeModeStreams,
    private val sent: CodeModeUpstreamHistory,
    private val batches: CodeModeBatchDispatch,
) {
    suspend fun start(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        stream: CodeModeLiveRound?,
    ): Pair<CodeModeRecord, TurnOutcome> {
        if (!batches.start(record, sent.clientStart(context))) return record to failure(record, stream)
        val cell = starter.start(record, context, stream)
        val outcome = if (!registry.attach(record, cell)) {
            failure(record, stream)
        } else {
            machine.advance(
                CodeModeAdvanceRequest(
                    record,
                    context.turn,
                    context.disableParallel,
                    emptyList(),
                    streams.attach(record, context.link.sink),
                    stream,
                    context.recovery?.delivery(record),
                ),
            )
        }
        return record to outcome
    }

    private suspend fun failure(record: CodeModeRecord, stream: CodeModeLiveRound?): TurnOutcome = when {
        stream?.localFailure != null -> checkNotNull(stream.localFailure)
        stream?.sourceInterrupted == true -> stream.outcome()
        else -> TurnOutcome.Failure(
            record.error.orEmpty(),
            cause = FailureCause.INTERNAL,
            phase = FailurePhase.MID_OUTPUT,
            salvagedUsage = noRequestUsage,
        )
    }
}
