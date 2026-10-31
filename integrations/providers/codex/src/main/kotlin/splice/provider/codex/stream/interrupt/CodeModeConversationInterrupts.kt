package splice.provider.codex.stream.interrupt

import splice.provider.codex.CodeModeInterruptOutcome
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.state.CodeModeTurnIdentity

/** User interruption retires live execution while accepted callbacks retain their durable ownership. */
internal class CodeModeConversationInterrupts(private val registry: CodexCodeModeRegistry) {
    suspend fun interrupt(sessionId: String, conversationKey: String): CodeModeInterruptOutcome {
        require(sessionId.isNotBlank()) { "A client session identity is required" }
        val conversation = CodeModeTurnIdentity().conversationId(sessionId, conversationKey)
        val result = registry.nativeCells.interrupt(conversation, sessionId)
        if (result is CodeModeInterruptOutcome.Interrupted) {
            val ended = result.cells.filter { it.terminated && it.failure == null }.mapTo(HashSet()) { it.cellId }
            registry.interruptionRecords.interrupt(conversation, ended)
        }
        return result
    }
}
