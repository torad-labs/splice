// NEW: Promote-to-text + empty-turn honesty for StreamFinish (concentration,
// 2026-08-19). Same-package; StreamFinish keeps the L2 mirror and the sole
// terminal emit.
package splice.gateway.pipeline

import splice.core.turn.ErrorType
import splice.core.turn.ModelTextPicker
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.util.LogSink
import splice.gateway.wire.TurnTerminal

internal class StreamPromote(
    private val compact: StreamCompact,
    private val log: LogSink,
    private val honesty: StreamHonesty,
) {
    /** Apply promote-to-text / empty-compact / CX-09 empty-model. Returns a
     *  terminal tag when the turn ends here, null to continue to mirror+emit. */
    suspend fun apply(
        emitter: TurnTerminal,
        outcome: TurnOutcome.Success,
        meta: TurnMeta,
        elapsedMs: Long,
    ): String? {
        // DR-88 rider: these were vars mutated inside the promote branch (emittedText = true,
        // bodyText += picked.text) — dead stores both, the promote arm never re-reads them and the
        // model_text arm below is unreachable from it.
        val emittedText = outcome.emittedText
        val bodyText = outcome.bodyText

        // Promote model thinking → text when no text AND no tools (compact needs a text channel).
        if (!emittedText && !outcome.hasToolUse) {
            val picked = ModelTextPicker.pickModelText(outcome.thinkingText, outcome.bodyText)
            if (picked.text.isNotEmpty()) {
                log(
                    "[gateway] promote-to-text compact=${meta.compact} " +
                        "source=${picked.source} chars=${picked.text.length}\n",
                )
                emitter.addTextBlock(picked.text)
                if (meta.compact) compact.record(picked.source, elapsedMs, chars = picked.text.length)
            } else if (meta.compact) {
                // An empty compact is an ERROR, not an empty success (Claude Code would store a
                // blank summary and lose the thread). Never invent locally.
                compact.record("empty_model", elapsedMs, error = "api_error")
                emitter.emitError(ErrorType.API_ERROR, "claudex: compact returned no content from model — retry")
                return "empty_compact"
            } else if (honesty.nothingReachesTheClient(outcome, meta)) {
                emitter.emitError(ErrorType.API_ERROR, "claudex: model returned no content (empty response) — retry")
                return "empty_model"
            }
        } else if (meta.compact && emittedText) {
            compact.record("model_text", elapsedMs, chars = bodyText.length)
        }
        return null
    }
}
