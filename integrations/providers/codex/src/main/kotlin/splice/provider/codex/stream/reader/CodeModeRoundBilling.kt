// NEW: terminal source usage belongs to its posting row; an intentional cut belongs to the cutting client step.
package splice.provider.codex.stream.reader

import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.noRequestUsage
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.stream.CodeModeOutcomeAccumulator
import splice.provider.codex.stream.CodeModeStreams
import splice.upstream.PostingTurnRow
import splice.upstream.RowRelease

internal fun interface CodeModeRoundRecord {
    operator fun invoke(): CodeModeRecord?
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

/** Shares the round's lifecycle monitor so its posting claim cannot race the usage terminal. */
internal class CodeModeRoundBilling(
    private val lifecycle: CodeModeRoundLifecycle,
    private val registry: CodexCodeModeRegistry,
    private val record: CodeModeRoundRecord,
    private val log: LogSink,
) {
    private var postingRow: PostingTurnRow? = null

    @Volatile private var owed: CodeModeOwedRound? = null

    private var postingHeld = false
    private var readerEnded = false
    private var usageConsumed = false
    private var reported: Usage? = null
    private val unreported: Boolean get() = !usageConsumed && reported == null

    /** Whether a posting step's row still waits for this round's terminal ([claim]), which [settle] releases it with.
     *  Read without [lifecycle]: the registry's key lock is taken under it ([consume]), so a reader holding that lock
     *  must not take it. A row is owed before its step's last cell borrower releases, so no reclaim sees it late. */
    val owes: Boolean get() = owed != null

    fun start(row: PostingTurnRow?) = synchronized(lifecycle) { postingRow = row }

    /** A parsed terminal still belongs to its posting row when disposal prevented source staging. */
    fun reported(outcome: TurnOutcome) = synchronized(lifecycle) {
        reported = when (outcome) {
            is TurnOutcome.Success -> outcome.usage
            is TurnOutcome.Failure -> ((outcome.partial?.usage ?: noRequestUsage) + outcome.salvagedUsage)
                .takeIf { it.reported.isNotEmpty() }
            is TurnOutcome.ClientAbandoned -> outcome.salvagedUsage.takeIf { it.reported.isNotEmpty() }
        }
    }

    fun consume(current: CodeModeRecord): Usage? {
        val owner = record() ?: current
        val staged = registry.source.consume(owner)
        // A staged terminal has one durable consumption owner. Its reported copy is only for rejected staging.
        return (staged ?: reported.takeIf { owner.sourceState?.usage == null })?.also {
            reported = null
            usageConsumed = true
        }
    }

    /** Register a successful posting step before its last worker borrower releases.
     *  Retirement can then settle this row even when it removes the round before the later usage claim. */
    fun prepare(step: TurnOutcome) = synchronized(lifecycle) {
        if (readerEnded || postingHeld) return@synchronized
        if (usageConsumed || step !is TurnOutcome.Success) return@synchronized
        postingRow?.let {
            owed = CodeModeOwedRound(it.hold(), step)
            postingHeld = true
        }
    }

    fun claim(current: CodeModeRecord, step: TurnOutcome): Usage? = synchronized(lifecycle) {
        consume(current) ?: run {
            prepare(step)
            null
        }
    }

    /** A client's intentional cut is never charged to this row. An autonomous reader cut has no newer owner. */
    fun settle(readerEnd: Boolean, clientCut: Boolean) {
        val (due, usage) = synchronized(lifecycle) {
            if (readerEnd) readerEnded = true
            val due = owed ?: return checkpoint(readerEnd)
            val current = record() ?: return
            val usage = try {
                consume(current)
            } catch (error: CodeModePersistenceException) {
                log("[code-mode] source round billed later: its claim was not saved (${error::class.simpleName})")
                null
            }
            if (usage == null && !readerEnd) return
            owed = null
            val missing = noRequestUsage.copy(
                origin = noRequestUsage.origin.copy(history = noRequestUsage.origin.history.copy(cutRounds = 1)),
            )
                .takeIf { unreported && !clientCut }
            due to (usage ?: missing)
        }
        Cancellables.runCatchingBestEffort { due.settle(usage) }.onFailure { failure ->
            log("[code-mode] the posting turn's row was not released (${failure::class.simpleName})")
        }
    }

    /** No row holds this terminal (a failed startup's retry owns it), so its consuming save never runs. The parsed
     *  terminal saves the finished source itself; a failed save is retried by the next sweep. */
    private fun checkpoint(readerEnd: Boolean) {
        if (readerEnd) return
        val current = record() ?: return
        try {
            registry.source.checkpoint(current)
        } catch (error: CodeModePersistenceException) {
            log("[code-mode] finished source saved later: its checkpoint was not saved (${error::class.simpleName})")
        }
    }
}
