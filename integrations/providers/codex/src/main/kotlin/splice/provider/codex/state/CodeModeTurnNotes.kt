// NEW: history diagnostics are emitted once per conversation, independently of dispatch decisions.
package splice.provider.codex.state

import splice.core.util.LogSink
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeRegistry
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
        val ordinary = owners.unknown.count { it in remainingCallbacks }
        val state = when {
            ordinary > 0 -> "ordinary"
            owners.unknown.isNotEmpty() -> "reconstructed"
            else -> "retained"
        }
        val changed = synchronized(announced) {
            val previous = announced.put(context.key, state)
            if (announced.size > NOTE_CACHE_KEYS) announced.remove(announced.keys.first())
            previous != state
        }
        if (changed && state == "ordinary") {
            log(
                "[code-mode] $ordinary tool result(s) remain ordinary after history reconstruction " +
                    "for conversation ${context.key.take(NOTE_KEY_CHARS)}",
            )
        } else if (changed && state == "reconstructed") {
            log(
                "[code-mode] durable history reconstructed ${owners.unknown.size} result(s) " +
                    "without execution records " +
                    "for conversation ${context.key.take(NOTE_KEY_CHARS)}",
            )
        }
    }
}

private const val NOTE_CACHE_KEYS = 256
private const val NOTE_KEY_CHARS = 8
