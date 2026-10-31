package splice.provider.codex.stream.source

import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModeRecord

/** Commits executable source and its response membership before the source cursor is released. */
internal fun interface CodeModeItemSeal {
    fun seal(
        record: CodeModeRecord,
        call: GatewayCustomCall,
        continuity: CodeModeContinuity?,
        snapshot: TurnOutcome.Success?,
    ): Boolean
}
