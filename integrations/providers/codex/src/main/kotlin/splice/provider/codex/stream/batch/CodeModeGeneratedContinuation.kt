package splice.provider.codex.stream.batch

import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRewrite
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeOutcomeAccumulator
import splice.provider.codex.stream.CodeModeStreams

internal fun interface CodeModeBatchStart {
    suspend fun start(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        round: CodeModeLiveRound?,
    ): Pair<CodeModeRecord, TurnOutcome>
}
internal fun interface CodeModeBatchPost {
    suspend fun post(context: CodeModeRunContext, outer: GatewayCustomCall?, body: CodeModeBody): TurnOutcome
}
internal fun interface CodeModeBatchRewrite {
    suspend fun rewrite(context: CodeModeRunContext, record: CodeModeRecord, body: CodeModeBody): CodeModeRewrite
}
internal fun interface CodeModeBatchRefusal {
    fun refuse(message: String): TurnOutcome.Failure
}
internal data class CodeModeGeneratedActions(
    val start: CodeModeBatchStart,
    val post: CodeModeBatchPost,
    val rewrite: CodeModeBatchRewrite,
    val refuse: CodeModeBatchRefusal,
)

/** Finishing an observation advances its sealed siblings before it can request another model response. */
internal class CodeModeGeneratedContinuation(
    private val streams: CodeModeStreams,
    private val batches: CodeModeBatchDispatch,
    private val actions: CodeModeGeneratedActions,
) {
    suspend fun finish(record: CodeModeRecord, context: CodeModeRunContext, body: CodeModeBody): TurnOutcome {
        val round = streams.find(record)
        if (context.completed.none { it.id == record.id }) context.completed += record
        val next = if (round == null) batches.next(record) else round.next(record)
        return if (next == null) {
            val generated = streams.takeOutcome(record)
            context.recovery?.generated(generated)
            complete(record, context, body, generated)
        } else {
            advance(record, next, context, body, round)
        }
    }

    private suspend fun advance(
        record: CodeModeRecord,
        next: CodeModeRecord,
        context: CodeModeRunContext,
        body: CodeModeBody,
        round: CodeModeLiveRound?,
    ): TurnOutcome {
        if (round != null) streams.keep(next, round, record)
        val (_, advanced) = actions.start.start(next, context, round)
        val result = if (next.phase == CodeModePhase.COMPLETED) {
            finish(next, context, body)
        } else {
            streams.billFinished(next, advanced)
        }
        return result
    }

    private suspend fun complete(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        body: CodeModeBody,
        generated: TurnOutcome?,
    ): TurnOutcome {
        batches.settle(record)
        val terminal = when (generated) {
            is TurnOutcome.Success -> generated.takeIf { it.incomplete }
            is TurnOutcome.Failure, is TurnOutcome.ClientAbandoned -> generated
            null -> null
        }
        return terminal ?: post(record, context, body, generated)
    }

    private suspend fun post(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        body: CodeModeBody,
        generated: TurnOutcome?,
    ): TurnOutcome {
        val rewritten = actions.rewrite.rewrite(context, record, body)
        rewritten.error?.let { return actions.refuse.refuse(it) }
        return accumulate(generated, actions.post.post(context, null, checkNotNull(rewritten.body)))
    }

    private fun accumulate(generated: TurnOutcome?, result: TurnOutcome): TurnOutcome {
        val accumulated = CodeModeOutcomeAccumulator()
        if (generated is TurnOutcome.Success) accumulated.absorb(generated)
        return accumulated.finish(result)
    }
}
