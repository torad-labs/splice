package splice.provider.codex.stream.batch

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CodeModeAdvanceRequest
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeMachine
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.history.CodeModeUpstreamHistory
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeRuntimeSelection
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
    suspend fun selected(
        record: CodeModeRecord,
        selected: CodeModeRuntimeSelection,
        context: CodeModeRunContext,
        stream: CodeModeLiveRound?,
        restarting: CodeModeBatchStart,
    ): Pair<CodeModeRecord, TurnOutcome> = if (selected.record.id != record.id) {
        val round = checkNotNull(stream)
        streams.keep(selected.record, round, record)
        restarting.start(selected.record, context, round)
    } else {
        when (selected) {
            is CodeModeRuntimeSelection.Refused -> record to starter.refused(record, selected.reason)
            is CodeModeRuntimeSelection.Ready -> start(record, context, stream)
        }
    }

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
            cause = FailureCause.Splice.Internal(),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence(record.error.orEmpty()),
            salvagedUsage = noRequestUsage,
        )
    }
}
