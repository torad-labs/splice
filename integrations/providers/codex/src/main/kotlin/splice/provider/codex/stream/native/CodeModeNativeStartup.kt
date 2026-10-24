package splice.provider.codex.stream.native

import splice.provider.codex.CODE_MODE_WAIT_TOOL_NAME
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.stream.CodeModeHeldNativeCell
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeNativeInvocation
import splice.provider.codex.stream.CodeModeNativeInvocations
import splice.provider.codex.stream.CodeModeNativeObservation
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.NativeCodeModeLifecycle
import java.io.IOException
/** Native exec and wait dispatch, including ownership of retained native cells. */
internal class CodeModeNativeStartup(private val registry: CodexCodeModeRegistry) {
    suspend fun start(
        host: NativeCodeModeLifecycle,
        record: CodeModeRecord,
        context: CodeModeRunContext,
        stream: CodeModeLiveRound?,
    ): CodeModeCell {
        record.nativeRuntime = true
        val owner = record.conversationId ?: splice.provider.codex.state.CodeModeTurnIdentity().conversationId(
            context.turn,
        )
        val source = if (stream == null) {
            record.origin.source
        } else {
            when (val whole = stream.sourceFor(record).view().read()) {
                is CodeModeSourcePart.Complete -> whole.text
                is CodeModeSourcePart.Failed -> throw IOException("Native code-mode source did not complete")
            }
        }
        val waiting = splice.core.util.JsonScalars.str(record.origin.outer, "name") == CODE_MODE_WAIT_TOOL_NAME
        val invocation = if (waiting) CodeModeNativeInvocations.wait(source) else CodeModeNativeInvocations.exec(source)
        val held = if (waiting) {
            retained(record, invocation, owner)
        } else {
            require(invocation.options.maxOutputTokens <= Int.MAX_VALUE.toLong()) {
                "Native exec output token budget exceeds the pinned host protocol range"
            }
            val cell = host.startNativeSession(
                owner,
                checkNotNull(invocation.source),
                context.turn.tools,
                context.turn.descriptions,
                invocation.options,
            )
            CodeModeHeldNativeCell(cell, record.origin.outerCallId)
        }
        registry.nativeCells.trackSession(record.key, owner, context.turn.sessionId)
        record.nativeCellId = held.cell.cellId
        return CodeModeNativeObservation(
            owner,
            held.cell,
            invocation,
            registry.nativeCells,
            held.notificationCallId,
        )
    }

    private fun retained(
        record: CodeModeRecord,
        invocation: CodeModeNativeInvocation,
        owner: String,
    ): CodeModeHeldNativeCell {
        val id = checkNotNull(invocation.cellId)
        val terminal = registry.completed(record.key, record.conversationId).firstOrNull {
            it.nativeCellId == id && it.nativeCompleted && it.phase == CodeModePhase.COMPLETED
        }
        terminal?.let { registry.nativeCells.completed(owner, id, it.origin.outerCallId) }
        return registry.nativeCells.take(owner, id)
    }
}
