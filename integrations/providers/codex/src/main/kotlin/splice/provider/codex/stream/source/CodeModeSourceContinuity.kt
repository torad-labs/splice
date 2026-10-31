// NEW: an item-sealed native prefix survives a terminal that supplies only the legacy flat receipt.
package splice.provider.codex.stream.source

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.history.CodeModeContinuityCapture
import splice.provider.codex.stream.CodeModeRecoveryHistory

internal class CodeModeSourceContinuity(private val wire: CodexCodeModeWire) {
    private var sealedPrefix: List<JsonObject>? = null
    private var verifiedAfterExec: List<JsonObject>? = null
    private var captured: CodeModeContinuity? = null
    private val delivery = CodeModeSourceDelivery()
    private var prefixKnown = false
    private var beforeText = ""

    /** The first completed call seals a proven arrival-order prefix independently of native output indexes. */
    @Volatile var deliveredNative: List<JsonElement>? = null
        private set

    fun sealed(snapshot: TurnOutcome.Success?) {
        sealedPrefix = snapshot?.let(CodeModeContinuityCapture::successItems)
        verifiedAfterExec = snapshot?.handoffs?.nativeAfterExec?.toList()
        prefixKnown = snapshot != null
    }

    fun capture(
        outcome: TurnOutcome.Success,
        recovery: CodeModeRecoveryHistory.Source?,
        witness: CodeModeDeliveredText? = null,
    ): CodeModeContinuity {
        if (deliveredNative == null) {
            outcome.handoffs.nativeDelivery?.deliveredPrefix?.let { prefix ->
                deliveredNative = CodeModeContinuityCapture.project(prefix).replayItems.flatMap { it.items }
            }
        }
        witness?.let(delivery::accept)
        outcome.handoffs.nativeAfterExec?.let { verifiedAfterExec = it.toList() }
        val kept = outcome.copy(
            handoffs = outcome.handoffs.copy(
                nativePrefix = sealedPrefix ?: outcome.handoffs.nativePrefix,
                nativeAfterExec = verifiedAfterExec,
            ),
        )
        prefixKnown = kept.handoffs.nativePrefix != null
        beforeText = CodeModeContinuityCapture.text(CodeModeContinuityCapture.successItems(kept))
        val source = recovery?.finish(kept, wire) ?: wire.continuity(kept)
        return fill(source).also {
            captured = it
            recovery?.afterExec(it.nativeAfterExec)
        }
    }

    /** The existing sink witness is bounded across attachments and is sampled only at a client-visible cutoff. */
    fun delivered(witness: CodeModeDeliveredText, recovery: CodeModeRecoveryHistory.Source?): CodeModeContinuity? {
        delivery.accept(witness)
        return captured?.let(::fill)?.also {
            captured = it
            recovery?.afterExec(it.nativeAfterExec)
        }
    }

    fun partial(partial: TurnOutcome.PartialRound?, recovery: CodeModeRecoveryHistory.Source?): CodeModeContinuity? {
        val after = partial?.nativeAfterExec ?: return null
        verifiedAfterExec = after.toList()
        return captured?.copy(nativeAfterExec = after)?.let(::fill)?.also {
            captured = it
            recovery?.afterExec(it.nativeAfterExec)
        }
    }

    private fun fill(source: CodeModeContinuity): CodeModeContinuity {
        if (!prefixKnown) return source.copy(nativeAfterExec = null)
        return source.copy(nativeAfterExec = delivery.tail(beforeText, verifiedAfterExec))
    }
}
