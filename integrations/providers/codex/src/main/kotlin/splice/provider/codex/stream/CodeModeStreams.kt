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
import splice.provider.codex.CodeModeOutcomeAccumulator
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.upstream.LifecycleScope
import splice.upstream.RowRelease
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.sse.WireSink
import java.util.concurrent.ConcurrentHashMap

/** Source stops stay on the ending thread, after its outermost registry scope releases every lock. */
internal object CodeModeSourceEnds {
    private val pending = ThreadLocal<MutableList<CodeModeSourceLease>>()

    fun defer(lease: CodeModeSourceLease?) {
        if (lease == null) return
        lease.retire()
        val held = pending.get()
        if (held == null) lease.ended() else held.add(lease)
    }

    inline fun <T> unlocked(block: () -> T): T {
        if (pending.get() != null) return block()
        val leases = mutableListOf<CodeModeSourceLease>()
        pending.set(leases)
        return try {
            block()
        } finally {
            pending.remove()
            leases.forEach(CodeModeSourceLease::ended)
        }
    }
}

/** A posting step's row, owed the source round that was still streaming when the step returned. */
internal class CodeModeOwedRound(private val release: RowRelease, private val step: TurnOutcome.Success) {
    /** Releases the row with [round] merged into the step's usage the way [CodeModeStreams.billFinished] merges a
     *  round that finished in time, or with none when the round left nothing to bill. */
    fun settle(round: Usage?) {
        val usage = round?.let {
            val accumulated = CodeModeOutcomeAccumulator()
            accumulated.absorb(TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = it))
            (accumulated.finishLocal(step) as? TurnOutcome.Success)?.usage
        }
        release.release(usage)
    }
}

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
        context.sink,
        headStop = context.headStop,
    ).also { round ->
        reading += round
        context.postedSources += round
        round.start(scope, post, body) { reading.remove(round) }
    }

    fun keep(record: CodeModeRecord, round: CodeModeLiveRound) {
        rounds[record.id] = round
        record.sourceEnd = CodeModeSourceLease(record.id, round, rounds)
    }

    fun find(record: CodeModeRecord): CodeModeLiveRound? = rounds[record.id]

    fun owns(turn: CodexCodeModeBridge.Turn): Boolean = rounds.values.any { it.owns(turn) }

    suspend fun attach(record: CodeModeRecord, sink: WireSink): WireSink {
        val round = rounds[record.id] ?: return sink
        round.switching.attach(sink)
        return CodeModeClientStepSink(round.switching, sink)
    }

    suspend fun takeOutcome(record: CodeModeRecord): TurnOutcome? {
        val round = rounds[record.id]
        val raw = try {
            round?.outcome()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (record.phase != splice.provider.codex.CodeModePhase.COMPLETED) throw error
            null
        }
        val usage = registry.source.consume(record)
        if (round != null) rounds.remove(record.id, round)
        record.sourceEnd = null
        round?.switching?.detach()
        return when (raw) {
            is TurnOutcome.Success -> raw.copy(usage = usage ?: Usage())
            null -> usage?.let { TurnOutcome.Success(false, false, it) }
            else -> raw
        }
    }

    /** Capture live sources before this turn's reconciliation can supersede or abandon their records. */
    fun watchCuts(key: String): Map<String, CodeModeLiveRound> = rounds.filterValues { it.key == key }

    /** Only the client step that cancelled an actual posted reader consumes its cut, once. */
    fun takeCuts(watched: Map<String, CodeModeLiveRound>, posted: List<CodeModeLiveRound>): Long {
        val cut = watched.filterValues(CodeModeLiveRound::takeCut)
        cut.forEach { (id, round) -> rounds.remove(id, round) }
        return cut.size.toLong() + posted.count(CodeModeLiveRound::takeCut)
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

    private fun addCuts(usage: Usage, cut: Long): Usage = usage.copy(cutRounds = usage.cutRounds + cut)

    /** A source round is billed on the client step that posted it. One that finished before the step ended is merged
     *  into the step here. One still streaming then is owed to the step's row, which waits for the round's terminal
     *  ([CodeModeRoundBilling.claim]); the client never waits for it. [CodeModeSourceRecords.consume] hands a round's
     *  usage out once, so the step that finishes the script ([takeOutcome]) finds nothing left to absorb. A claim that
     *  cannot be saved leaves the round to that later step rather than failing a step whose calls already left. */
    fun billFinished(record: CodeModeRecord, step: TurnOutcome): TurnOutcome {
        val round = rounds[record.id]
        val usage = try {
            if (round != null) round.billing.claim(record, step) else registry.source.consume(record)
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
        if (record.phase == splice.provider.codex.CodeModePhase.LOST) {
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
