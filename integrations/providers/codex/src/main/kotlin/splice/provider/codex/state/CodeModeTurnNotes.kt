// NEW: history diagnostics are emitted once per conversation, independently of dispatch decisions.
package splice.provider.codex.state

import splice.core.util.LogSink
import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeRegistry
import splice.upstream.codemode.CodeModeResult
import java.util.concurrent.ConcurrentHashMap

internal class CodeModeTurnNotes(
    private val registry: CodexCodeModeRegistry,
    private val log: LogSink,
) {
    private val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun announce(context: CodeModeRunContext) {
        notes(context).filter { announced.add("${context.key}|$it") }.forEach { log("[code-mode] $it") }
    }

    private fun notes(context: CodeModeRunContext): List<String> {
        val resultIds = context.turn.toolResults.map(CodeModeResult::id).toSet()
        val owners = registry.resultOwners(context.key, resultIds)
        return buildList {
            if (registry.expiredHistory(context.key, context.digest, resultIds)) {
                add("expired code-mode history for this conversation; its client calls stay ordinary tool calls")
            }
            owners.foreign?.let { foreign ->
                add(
                    "code-mode results in this history belong to another session or model " +
                        "(record ${foreign.id.take(CODE_MODE_RECORD_LOG_CHARS)}); they stay ordinary tool calls",
                )
            }
            if (owners.unknown.isNotEmpty()) {
                add("unknown or expired code-mode tool results stay ordinary tool calls: ${owners.unknown}")
            }
        }
    }
}
