// NEW: history diagnostics are emitted once per conversation, independently of dispatch decisions.
package splice.provider.codex.state

import splice.core.util.LogSink
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.state.diagnostics.CODE_MODE_RECORD_LOG_CHARS
import splice.upstream.codemode.CodeModeResult

internal class CodeModeTurnNotes(
    private val registry: CodexCodeModeRegistry,
    private val log: LogSink,
) {
    private val announced = LinkedHashMap<String, String>(NOTE_CACHE_KEYS, 1f, true)

    /** Inspect the body after durable reconstruction, so a missing hot record does not imply fallback. */
    fun announce(context: CodeModeRunContext, remainingCallbacks: Set<String>) {
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        val owners = registry.resultOwners(context.key, resultIds)
        val ordinary = owners.unknown.intersect(remainingCallbacks)
        val reason = ordinaryReason(context, ordinary, owners.foreign != null)
        val state = when {
            ordinary.isNotEmpty() -> "ordinary:$reason"
            owners.unknown.isNotEmpty() -> "reconstructed"
            else -> "retained"
        }
        val changed = synchronized(announced) {
            val previous = announced.put(context.key, state)
            if (announced.size > NOTE_CACHE_KEYS) announced.remove(announced.keys.first())
            previous != state
        }
        if (changed && ordinary.isNotEmpty()) {
            log(
                "[code-mode] ${ordinary.size} tool result(s) remain ordinary after history reconstruction " +
                    "for conversation ${context.key.take(CODE_MODE_RECORD_LOG_CHARS)}: $reason",
            )
        } else if (changed && state == "reconstructed") {
            log(
                "[code-mode] durable history reconstructed ${owners.unknown.size} result(s) " +
                    "without execution records " +
                    "for conversation ${context.key.take(CODE_MODE_RECORD_LOG_CHARS)}",
            )
        }
    }

    private fun ordinaryReason(context: CodeModeRunContext, ids: Set<String>, foreign: Boolean): String = when {
        ids.isEmpty() -> ""
        foreign -> "results belong to another session or model"
        registry.expiredHistory(context.key, context.digest, ids) -> "expired code-mode history"
        else -> "unknown execution records"
    }
}

// why: retain transitions for hundreds of concurrent conversations without keeping every historical warning forever.
private const val NOTE_CACHE_KEYS = 256
