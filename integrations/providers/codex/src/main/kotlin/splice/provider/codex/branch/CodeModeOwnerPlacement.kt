package splice.provider.codex.branch

import splice.core.util.LogSink
import splice.provider.codex.CODE_MODE_ABANDONED
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeMachine
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeWire
import splice.provider.codex.state.diagnostics.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.state.diagnostics.CodeModeHistoryLog
import splice.provider.codex.state.diagnostics.CodeModeNativeRejection
import splice.upstream.codemode.CodeModeResult
internal data class CodeModePlacedOwner(val record: CodeModeRecord, val body: CodeModeBody)

/** Resolves a parked script against client history and retires its execution when its baseline was replaced. */
internal class CodeModeOwnerPlacement(
    private val registry: CodexCodeModeRegistry,
    private val wire: CodexCodeModeWire,
    private val machine: CodexCodeModeMachine,
    private val log: LogSink,
) {
    /** The active or lost owner whose baseline still places in this history, with that history
     *  restored around it — or null when there is none, or when the one there was is abandoned. */
    fun find(context: CodeModeRunContext, canonicalBody: CodeModeBody): CodeModePlacedOwner? {
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        val callbackIds = wire.callbackIds(canonicalBody)
        val owner = registry.owner(context.key, context.digest, resultIds, callbackIds, context.conflicts)
            ?: return null
        val restored = wire.restoreBaseline(canonicalBody, owner)
        val error = restored.error ?: return CodeModePlacedOwner(owner, checkNotNull(restored.body))
        context.abandonedSources += owner
        abandon(owner, error, restored.nativeRejection)
        return null
    }

    /** A running script whose history moved underneath it is abandoned: cell closed, evidence kept
     *  on the LOST record, and the turn continues upstream on the client's own history. */
    fun abandon(owner: CodeModeRecord, error: String, rejection: CodeModeNativeRejection?) {
        val detail = "$CODE_MODE_ABANDONED: $error; source was not rerun"
        registry.lose(owner, detail)
        // History only grows, so a record that no longer places never will again: it retires with its
        // evidence now. Left LOST it was found by its client ids and abandoned again on every later
        // turn of the conversation (82 identical lines for one record on 2026-09-20).
        machine.interrupt(owner, detail)
        log(
            "[code-mode] abandoned record ${owner.id.take(CODE_MODE_RECORD_LOG_CHARS)} " +
                "(outer ${owner.origin.outerCallId}): " +
                "$error; continuing upstream on the client's history; ${CodeModeHistoryLog.context(owner, rejection)}",
        )
    }
}
