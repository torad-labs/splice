// NEW: head stop, not the first client step, ends independently owned code generation.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.stream.batch.CodeModeBatchHistory
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.sse.WireSink
import java.util.concurrent.ConcurrentHashMap

/** Ending an execution lease cancels its response reader and releases its upstream slot. */
internal class CodeModeSourceLease(
    private val id: String,
    private val round: CodeModeLiveRound,
    private val rounds: ConcurrentHashMap<String, CodeModeLiveRound>,
    private val beforeEnd: Runnable? = null,
) {
    /** Supersession is owned by the client step, not by the autonomous park mechanism it uses. */
    fun claimClient() = round.cut.claimClient()

    /** Publish before a retained cell closes, even when actual cancellation waits for registry unlock. */
    fun retire() = round.cut.retire()

    /** A posting step's row still waits for this round's terminal: ending the lease now cancels the round and releases
     *  that row with none of the round's usage, only a cut. */
    val owed: Boolean get() = round.billing.owes

    /** The round's reader is still running, so a row may yet be owed its usage. */
    val reading: Boolean get() = !round.readerSettled

    /** Lets the round go without cutting it: no record or step reaches it again, and its reader runs to the terminal,
     *  which still bills the row it is owed ([CodeModeRoundBilling.reported] when its record is gone). A head stop still
     *  stops it. Closing the retained cell after this cuts nothing (round 15 of #247). */
    fun release() {
        round.cut.release()
        rounds.remove(id, round)
    }

    fun ended() {
        retire()
        beforeEnd?.run()
        rounds.remove(id, round)
        round.cancel()
    }
}

internal class CodeModeStreams(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
) {
    private val scope = LifecycleScope(ProcessDispatchers().io())
    private val rounds = ConcurrentHashMap<String, CodeModeLiveRound>()
    private val reading: MutableSet<CodeModeLiveRound> = ConcurrentHashMap.newKeySet()

    fun begin(
        context: CodeModeRunContext,
        body: CodeModeBody,
        post: CodeModeRedirectablePost,
        admission: CodeModeStreamAdmission,
    ): CodeModeLiveRound = CodeModeLiveRound(
        config,
        registry,
        wire,
        admission,
        context.link.sink,
        headStop = context.headStop,
        recovery = context.recovery,
    ).also { round ->
        reading += round
        context.postedSources += round
        round.start(scope, post, body) { reading.remove(round) }
    }

    fun keep(record: CodeModeRecord, round: CodeModeLiveRound, predecessor: CodeModeRecord? = null) {
        predecessor?.let {
            rounds.remove(it.id, round)
            it.sourceEnd = null
        }
        rounds[record.id] = round
        record.sourceEnd = CodeModeSourceLease(record.id, round, rounds)
    }

    fun find(record: CodeModeRecord): CodeModeLiveRound? = rounds[record.id]

    fun owns(turn: CodexCodeModeBridge.Turn): Boolean = rounds.values.any { it.owns(turn) }

    suspend fun attach(record: CodeModeRecord, sink: WireSink): WireSink {
        val round = rounds[record.id] ?: return sink
        if (sink is CodeModeClientStepSink && sink.belongsTo(round.switching)) return sink
        round.switching.attach(sink)
        return CodeModeClientStepSink(round.switching, sink)
    }

    suspend fun takeOutcome(record: CodeModeRecord): TurnOutcome? {
        val round = rounds[record.id]
        val raw = try {
            round?.outcome()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (record.phase != CodeModePhase.COMPLETED) throw error
            null
        }
        val usage = if (round == null) {
            registry.source.consume(CodeModeBatchHistory.owner(record))
        } else {
            round.billing.consume(record)
        }
        if (round != null) rounds.remove(record.id, round)
        record.sourceEnd = null
        round?.switching?.detach()
        return CodeModeBilledOutcome.merge(raw, usage)
    }

    /** Capture live sources before this turn's reconciliation can supersede or abandon their records. */
    fun watchCuts(key: String): Map<String, CodeModeLiveRound> = rounds.filterValues { it.key == key }

    /** Only the client step that cancelled an actual posted reader consumes its cut, once. */
    fun takeCuts(watched: Map<String, CodeModeLiveRound>, posted: List<CodeModeLiveRound>): Long {
        val cut = watched.filterValues { it.cut.take() }
        cut.forEach { (id, round) -> rounds.remove(id, round) }
        return cut.size.toLong() + posted.count { it.cut.take() }
    }

    fun billCuts(
        watched: Map<String, CodeModeLiveRound>,
        posted: List<CodeModeLiveRound>,
        outcome: TurnOutcome,
    ): TurnOutcome {
        val cut = takeCuts(watched, posted)
        if (cut == 0L) return outcome
        // A cut is accounting, not generated content: preserve the failure's existing re-anchor boundary.
        return when (outcome) {
            is TurnOutcome.Success -> outcome.copy(usage = addCuts(outcome.usage, cut))
            is TurnOutcome.Failure -> outcome.copy(
                partial = outcome.partial?.let { it.copy(usage = addCuts(it.usage, cut)) },
                salvagedUsage = addCuts(outcome.salvagedUsage, cut),
            )
            is TurnOutcome.ClientAbandoned -> outcome.copy(salvagedUsage = addCuts(outcome.salvagedUsage, cut))
        }
    }

    private fun addCuts(usage: Usage, cut: Long): Usage =
        usage.copy(origin = usage.origin.copy(history = usage.origin.history.copy(cutRounds = usage.cutRounds + cut)))

    /** A source round is billed on the client step that posted it. One that finished before the step ended is merged
     *  into the step here. One still streaming then is owed to the step's row, which waits for the round's terminal
     *  ([CodeModeRoundBilling.claim]); the client never waits for it. [CodeModeSourceRecords.consume] hands a round's
     *  usage out once, so the step that finishes the script ([takeOutcome]) finds nothing left to absorb. A claim that
     *  cannot be saved leaves the round to that later step rather than failing a step whose calls already left.
     *  Abandonment supplies its captured round so disposal cannot erase an unheld terminal before this claim. */
    fun billFinished(
        record: CodeModeRecord,
        step: TurnOutcome,
        round: CodeModeLiveRound? = rounds[record.id],
    ): TurnOutcome {
        val usage = try {
            if (round != null) {
                round.billing.claim(
                    record,
                    step,
                )
            } else {
                registry.source.consume(CodeModeBatchHistory.owner(record))
            }
        } catch (error: CodeModePersistenceException) {
            config.log("[code-mode] source round billed later: its claim was not saved (${error::class.simpleName})")
            null
        } ?: return step
        val accumulated = CodeModeOutcomeAccumulator()
        accumulated.absorb(TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = usage))
        return accumulated.finishLocal(step)
    }

    suspend fun endStep(record: CodeModeRecord) {
        val round = rounds[record.id] ?: return
        round.switching.detach()
        if (record.phase == CodeModePhase.LOST) {
            rounds.remove(record.id, round)
            round.cancel()
        }
    }

    fun discard(record: CodeModeRecord) {
        record.sourceEnd?.ended()
        record.sourceEnd = null
    }

    fun stop() {
        reading.forEach(CodeModeLiveRound::stop)
        scope.coroutineContext.cancelChildren()
        rounds.clear()
    }
}
