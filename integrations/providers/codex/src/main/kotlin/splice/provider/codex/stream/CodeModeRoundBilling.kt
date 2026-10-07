// NEW: terminal source usage belongs to its posting row; an intentional cut belongs to the cutting client step.
package splice.provider.codex.stream

import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.upstream.PostingTurnRow

internal fun interface CodeModeRoundRecord {
    operator fun invoke(): CodeModeRecord?
}

/** Shares the round's lifecycle monitor so its posting claim cannot race the usage terminal. */
internal class CodeModeRoundBilling(
    private val lifecycle: Any,
    private val registry: CodexCodeModeRegistry,
    private val record: CodeModeRoundRecord,
    private val log: LogSink,
) {
    private var postingRow: PostingTurnRow? = null
    private var owed: CodeModeOwedRound? = null
    private var postingHeld = false
    private var readerEnded = false
    private var usageConsumed = false
    private var reported: Usage? = null
    private val unreported: Boolean get() = !usageConsumed && reported == null

    fun start(row: PostingTurnRow?) = synchronized(lifecycle) { postingRow = row }

    /** A parsed terminal still belongs to its posting row when disposal prevented source staging. */
    fun reported(outcome: TurnOutcome) = synchronized(lifecycle) {
        reported = (outcome as? TurnOutcome.Success)?.usage
    }

    private fun consume(current: CodeModeRecord): Usage? {
        val staged = registry.source.consume(current)
        // A staged terminal has one durable consumption owner. Its reported copy is only for rejected staging.
        return (staged ?: reported.takeIf { current.sourceState?.usage == null })?.also {
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
            val due = owed ?: return
            val current = record() ?: return
            val usage = try {
                consume(current)
            } catch (error: CodeModePersistenceException) {
                log("[code-mode] source round billed later: its claim was not saved (${error::class.simpleName})")
                null
            }
            if (usage == null && !readerEnd) return
            owed = null
            due to (usage ?: Usage(cutRounds = 1, reported = emptySet()).takeIf { unreported && !clientCut })
        }
        Cancellables.runCatchingBestEffort { due.settle(usage) }.onFailure { failure ->
            log("[code-mode] the posting turn's row was not released (${failure::class.simpleName})")
        }
    }
}
