package splice.provider.codex.stream.native

import splice.provider.codex.CODE_MODE_WAIT_TOOL_NAME
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.catalog.CodeModeCatalogMessages
import splice.provider.codex.stream.CodeModeArgumentException
import splice.provider.codex.stream.CodeModeHeldNativeCell
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeNativeInvocation
import splice.provider.codex.stream.CodeModeNativeInvocations
import splice.provider.codex.stream.CodeModeNativeObservation
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeManual
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
        validatePayload(record, waiting)
        val invocation = if (waiting) {
            CodeModeNativeInvocations.wait(source)
        } else {
            CodeModeNativeInvocations.exec(
                source,
                context.turn.toolPolicy.defaultExecYieldTimeMs,
            )
        }
        val held = if (waiting) {
            retained(host, record, invocation, owner).also {
                registry.notifications.bind(owner, it.notificationCallId, context)
                (it.cell as? CodeModeSearchCell)?.update(context.turn)
            }
        } else {
            val cell = execute(host, owner, invocation, context, record)
            CodeModeHeldNativeCell(cell, record.origin.outerCallId)
        }
        registry.nativeCells.services.trackSession(record.key, owner, context.turn.sessionId)
        registry.nativeCells.interrupts.started(owner, held)
        record.nativeCellId = held.cell.cellId
        return CodeModeNativeObservation(
            owner,
            held.cell,
            invocation,
            registry.nativeCells,
            held.notificationCallId,
            context.turn.toolPolicy,
        )
    }

    private suspend fun execute(
        host: NativeCodeModeLifecycle,
        owner: String,
        invocation: CodeModeNativeInvocation,
        context: CodeModeRunContext,
        record: CodeModeRecord,
    ): splice.upstream.codemode.NativeCodeModeCell {
        val options = invocation.options.copy(
            toolCallId = record.origin.outerCallId,
            notifications = registry.notifications.receiver(record, context, owner),
        )
        return if (context.turn.toolDefinitions.isEmpty()) {
            host.startNativeSession(
                owner,
                checkNotNull(invocation.source),
                context.turn.tools,
                context.turn.descriptions,
                options,
            )
        } else {
            val definitions = CodeModeCatalogMessages(
                context.turn.toolPolicy.modelMessages,
            ).namespacePrefixes.codeMode(
                CodeModeManual.prepare(
                    context.turn.toolDefinitions,
                    context.turn.toolPolicy.toolDescriptionFirst,
                ),
            )
            CodeModeSearchCell(
                host.startNativeSession(owner, checkNotNull(invocation.source), definitions, options),
                context.turn,
            )
        }
    }

    private suspend fun retained(
        host: NativeCodeModeLifecycle,
        record: CodeModeRecord,
        invocation: CodeModeNativeInvocation,
        owner: String,
    ): CodeModeHeldNativeCell {
        val id = checkNotNull(invocation.cellId)
        val recorded = registry.completed(record.key, record.conversationId).filter { it.nativeCellId == id }
        val terminal = recorded.firstOrNull { it.nativeCompleted && it.phase == CodeModePhase.COMPLETED }
        terminal?.let { registry.nativeCells.completed(owner, id, it.origin.outerCallId) }
        registry.nativeCells.takeRetained(owner, id)?.let { return it }
        // A cell recorded running whose engine this process never held was lost with the process that ran it.
        // Codex's missing-cell result would mark it completed; the wait fails as lost, and its source is not rerun.
        check(terminal != null || recorded.isEmpty() || registry.nativeCells.owns(owner, id)) {
            "Native cell is unavailable; its source was not rerun"
        }
        val control = host.controlNativeSession(owner, id) ?: return registry.nativeCells.take(owner, id)
        return CodeModeHeldNativeCell(control, terminal?.origin?.outerCallId ?: record.origin.outerCallId)
    }

    private fun validatePayload(record: CodeModeRecord, waiting: Boolean) {
        val outer = record.origin.outer
        val name = splice.core.util.JsonScalars.str(outer, "name")
        if (name == splice.provider.codex.LEGACY_CODE_MODE_TOOL_NAME) return
        val type = splice.core.util.JsonScalars.str(outer, "type")
        val namespace = splice.core.util.JsonScalars.str(outer, "namespace")
        val expected = if (waiting) "function_call" else "custom_tool_call"
        val validNamespace = namespace == null || namespace in setOf("", "functions")
        val validType = type == null || type == expected
        if (!validType || !validNamespace) {
            val message = if (waiting) "wait expects JSON arguments" else "exec expects raw JavaScript source text"
            throw CodeModeArgumentException(message, IllegalArgumentException(message))
        }
    }
}
