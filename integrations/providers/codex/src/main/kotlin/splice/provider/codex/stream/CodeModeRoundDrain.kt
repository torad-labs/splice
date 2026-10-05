// NEW: disposed execution drains its response and releases terminal usage only to the original posting row.
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

internal fun interface CodeModeExecutionDisposed {
    operator fun invoke(): Boolean
}

/** Shares the round's lifecycle monitor so posting claims cannot race its usage terminal. */
internal class CodeModeRoundDrain(
    private val lifecycle: Any,
    private val registry: CodexCodeModeRegistry,
    private val record: CodeModeRoundRecord,
    private val log: LogSink,
) {
    private var postingRow: PostingTurnRow? = null
    private var owed: CodeModeOwedRound? = null
    private var readerEnded = false
    private var reported: Usage? = null

    @Volatile private var executionDisposed = false

    /** Completion, loss and lease eviction dispose execution independently of response lifetime. */
    val disposed: Boolean get() = executionDisposed || record()?.terminal() == true

    fun dispose() {
        executionDisposed = true
    }

    fun start(row: PostingTurnRow?) = synchronized(lifecycle) { postingRow = row }

    fun reported(outcome: TurnOutcome) = synchronized(lifecycle) {
        reported = (outcome as? TurnOutcome.Success)?.usage
    }

    fun claim(current: CodeModeRecord, step: TurnOutcome): Usage? = synchronized(lifecycle) {
        consume(current) ?: run {
            if (step is TurnOutcome.Success && !readerEnded) {
                postingRow?.takeIf { owed == null }?.let { owed = CodeModeOwedRound(it.hold(), step) }
            }
            null
        }
    }

    /** A disposed record cannot accept capture, but its response still reports independently owned usage. */
    private fun consume(current: CodeModeRecord): Usage? =
        (registry.source.consume(current) ?: reported)?.also { reported = null }

    /** Releases outside the monitor. A reader with no usage ended as a cut, never as an empty billed row. */
    fun settle(readerEnd: Boolean) {
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
            due to (usage ?: Usage(cutRounds = 1))
        }
        Cancellables.runCatchingBestEffort { due.settle(usage) }.onFailure { failure ->
            log("[code-mode] the posting turn's row was not released (${failure::class.simpleName})")
        }
    }
}
