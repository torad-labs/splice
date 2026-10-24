// NEW: an item-sealed native prefix survives a terminal that supplies only the legacy flat receipt.
package splice.provider.codex.stream

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodexCodeModeWire

internal class CodeModeSourceContinuity(private val wire: CodexCodeModeWire) {
    private var sealedPrefix: List<JsonObject>? = null

    fun sealed(snapshot: TurnOutcome.Success?) {
        sealedPrefix = snapshot?.handoffs?.nativePrefix?.toList()
    }

    fun capture(outcome: TurnOutcome.Success, recovery: CodeModeRecoveryHistory.Source?): CodeModeContinuity {
        val kept = if (outcome.handoffs.nativePrefix == null && sealedPrefix != null) {
            outcome.copy(handoffs = outcome.handoffs.copy(nativePrefix = sealedPrefix))
        } else {
            outcome
        }
        return recovery?.finish(kept, wire) ?: wire.continuity(kept)
    }
}
