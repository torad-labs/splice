// NEW: cell disposal cancels an unfinished source reader, while normal EOF preserves its terminal outcome.
package splice.provider.codex.stream

import splice.provider.codex.CodeModeRecord
import splice.upstream.codemode.CodeModeCell

internal class CodeModeStreamingCell(
    private val cell: CodeModeCell,
    private val record: CodeModeRecord,
    private val round: CodeModeLiveRound,
) : CodeModeCell by cell {
    override fun close() {
        cell.close()
        if (record.sourceState?.complete != true) round.cancel()
    }
}
