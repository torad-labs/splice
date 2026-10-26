// PORT-OF: codex-rs/code-mode/src/grpc_session/state.rs at rust-v0.164.0.
package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import splice.codemode.nativehost.grpc.state.NativeGrpcAbandoned
import splice.codemode.nativehost.grpc.state.NativeGrpcCallbackIds
import splice.codemode.nativehost.grpc.state.NativeGrpcCallbackState
import splice.codemode.nativehost.grpc.state.NativeGrpcExecutionRecord
import splice.codemode.nativehost.grpc.state.NativeGrpcPendingCallback
import java.io.IOException
import java.util.UUID

private const val NATIVE_GRPC_PENDING_CALLBACKS: Int = 1_024
private const val NATIVE_GRPC_EXECUTION_KEY: String = "execution_id"
private const val NATIVE_GRPC_CELL_KEY: String = "cell_id"

/** Admit cross-stream callbacks against their execution before publishing any client-visible work. */
internal class NativeGrpcExecutions(
    private val delegates: NativeGrpcDelegates,
    private val sink: NativeGrpcDelegateSink,
) {
    private val executions = HashMap<Pair<String, String>, NativeGrpcExecutionRecord>()
    private val operations = HashMap<Long, Pair<String, String>>()
    private val callbacks = HashMap<String, NativeGrpcCallbackState>()

    @Synchronized
    fun begin(session: NativeGrpcSession, operationId: Long, executionId: String, request: DynamicMessage) {
        val key = session.localId to executionId
        check(executionId.isNotEmpty() && key !in executions) { "code-mode execution ID was empty or reused" }
        val tools = NativeGrpcFields.messages(request, "enabled_tools").associate { definition ->
            val name = NativeGrpcFields.message(definition, "tool_name")
            NativeGrpcToolNames.key(name) to NativeGrpcFields.enumeration(definition, "kind").number
        }
        executions[key] = NativeGrpcExecutionRecord(
            session,
            operationId,
            executionId,
            NativeGrpcFields.identifier(request, "tool_call_id"),
            tools,
        )
        operations[operationId] = key
        if (session.localId !in callbacks) callbacks[session.localId] = NativeGrpcCallbackState()
    }

    @Synchronized
    fun started(session: NativeGrpcSession, operationId: Long, message: DynamicMessage) {
        val execution = requireNotNull(operations[operationId]?.let(executions::get)) { "Unknown code-mode execution" }
        val executionId = NativeGrpcFields.text(message, NATIVE_GRPC_EXECUTION_KEY)
        check(execution.session == session && executionId == execution.executionId) {
            "Code-mode execution changed its owner"
        }
        check(!execution.started) { "code-mode execution started twice" }
        val cell = NativeGrpcFields.identifier(message, NATIVE_GRPC_CELL_KEY)
        acceptCell(execution, cell)
        execution.started = true
        sink.emit(NativeGrpcFrames.response(operationId, NativeGrpcFrames.started(cell)))
    }

    @Synchronized
    fun ready(operationId: Long) {
        val execution = operations[operationId]?.let(executions::get) ?: return
        check(execution.started && !execution.ready) { "code-mode execution was not ready to be claimed" }
        execution.ready = true
        closeIfReady(execution)
    }

    @Synchronized
    fun call(session: NativeGrpcSession, message: DynamicMessage) {
        check(NativeGrpcFields.text(message, "session_id") == session.id) { "Code-mode callback crossed sessions" }
        val state = requireNotNull(callbacks[session.localId]) { "Code-mode session is closed" }
        val id = NativeGrpcFields.text(message, "invocation_id")
        val uuid = NativeGrpcCallbackIds.parse(id, "tool invocation")
        check(
            id !in state.invocations && !state.seenInvocations.contains(uuid),
        ) { "code-mode tool invocation ID was reused" }
        val key = session.localId to NativeGrpcFields.text(message, NATIVE_GRPC_EXECUTION_KEY)
        val cell = NativeGrpcFields.text(message, NATIVE_GRPC_CELL_KEY)
        val execution = executions[key]
        execution?.let { acceptCell(it, cell) }
        state.seenInvocations.remember(uuid)
        val cancelled = state.cancelledInvocations.remove(uuid)
        if (execution == null || execution.closed) {
            delegates.reject(session, message, "code-mode cell $cell is closed")
        } else if (!cancelled) {
            admitCall(execution, message, id, cell, state)
        } else {
            delegates.ignored(session, message)
        }
    }

    private fun admitCall(
        execution: NativeGrpcExecutionRecord,
        message: DynamicMessage,
        id: String,
        cell: String,
        state: NativeGrpcCallbackState,
    ) {
        val name = NativeGrpcFields.optional(message, "tool_name")
        val enabledKind = name?.let { execution.enabledTools[NativeGrpcToolNames.key(it)] }
        val kind = NativeGrpcFields.enumeration(message, "tool_kind").number
        val error = when {
            name == null -> "code-mode tool invocation omitted its tool name"
            enabledKind != kind ->
                "code-mode tool ${NativeGrpcToolNames.display(name)} is not enabled for this execution"
            state.invocations.size + state.notifications.size >= NATIVE_GRPC_PENDING_CALLBACKS ->
                "code-mode host exceeded its pending delegate callback limit"
            else -> null
        }
        if (error != null) {
            delegates.reject(execution.session, message, error)
        } else {
            state.invocations[id] = NativeGrpcPendingCallback(execution.session.localId to execution.executionId, cell)
            delegates.tool(execution.session, message)
        }
    }

    @Synchronized
    fun notification(session: NativeGrpcSession, message: DynamicMessage) {
        val id = NativeGrpcFields.text(message, "notification_id")
        NativeGrpcCallbackIds.parse(id, "notification")
        val call = NativeGrpcFields.identifier(message, "call_id")
        val key = session.localId to NativeGrpcFields.text(message, NATIVE_GRPC_EXECUTION_KEY)
        val execution = executions[key] ?: return
        val cell = NativeGrpcFields.identifier(message, NATIVE_GRPC_CELL_KEY)
        acceptCell(execution, cell)
        check(call == execution.toolCallId) { "code-mode notification call ID does not match its execution" }
        val state = requireNotNull(callbacks[session.localId])
        if (!execution.closed && state.invocations.size + state.notifications.size < NATIVE_GRPC_PENDING_CALLBACKS) {
            // Codex validates the wire UUID but counts each notification, including repeated UUIDs.
            val receipt = "notification:${UUID.randomUUID()}"
            state.notifications[receipt] = NativeGrpcPendingCallback(key, cell)
            delegates.notification(session, message, receipt)
        }
    }

    @Synchronized
    fun closed(session: NativeGrpcSession, message: DynamicMessage) {
        val key = session.localId to NativeGrpcFields.text(message, NATIVE_GRPC_EXECUTION_KEY)
        val execution = executions[key] ?: return
        val cell = NativeGrpcFields.identifier(message, NATIVE_GRPC_CELL_KEY)
        acceptCell(execution, cell)
        check(!execution.closed) { "code-mode host returned an invalid closure for cell $cell" }
        execution.closed = true
        // Host execution ended even when its caller has not claimed the buffered result yet.
        sink.emit(NativeGrpcFrames.closed(session.localId, cell))
        val state = requireNotNull(callbacks[session.localId])
        val ids = state.invocations.filterValues { it.execution == key }.keys.toList()
        ids.forEach { id ->
            state.invocations.remove(id)
            delegates.cancelled(session, id)
        }
        closeIfReady(execution)
    }

    @Synchronized
    fun cancelled(session: NativeGrpcSession, id: String) {
        val uuid = NativeGrpcCallbackIds.parse(id, "tool invocation")
        val state = callbacks[session.localId] ?: return
        if (state.invocations.remove(id) != null) {
            delegates.cancelled(session, id)
        } else if (!state.seenInvocations.contains(uuid)) {
            state.cancelledInvocations.remember(uuid)
        }
    }

    @Synchronized
    fun settled(session: NativeGrpcSession, cell: String, id: String) {
        val state = callbacks[session.localId] ?: return
        val pending = state.invocations.remove(id) ?: state.notifications.remove(id) ?: return
        check(pending.cell == cell) { "Code-mode callback completion changed its cell" }
        executions[pending.execution]?.let(::closeIfReady)
    }

    suspend fun awaitNotifications(session: NativeGrpcSession, cell: String) {
        val closure = synchronized(this) {
            executions.values.firstOrNull { it.session == session && it.cell == cell }?.notificationClosure
        }
        closure?.await()
    }

    @Synchronized
    fun abandon(operation: Long): NativeGrpcAbandoned? {
        val key = operations.remove(operation) ?: return null
        val execution = executions.remove(key) ?: return null
        val state = callbacks[execution.session.localId]
        val invocations = state?.invocations?.filterValues { it.execution == key }?.keys.orEmpty().toList()
        invocations.forEach { id ->
            state?.invocations?.remove(id)
            delegates.cancelled(execution.session, id)
        }
        state?.notifications?.entries?.removeIf { it.value.execution == key }
        execution.notificationClosure.complete(Unit)
        execution.cell?.let { cell ->
            delegates.terminateNotifications(execution.session, cell)
            if (!execution.closed) sink.emit(NativeGrpcFrames.closed(execution.session.localId, cell))
        }
        return NativeGrpcAbandoned(execution.session, execution.cell)
    }

    @Synchronized
    fun failed(session: NativeGrpcSession, error: Throwable) {
        val owned = executions.values.filter { it.session == session }
        owned.forEach { execution ->
            executions.remove(session.localId to execution.executionId)
            operations.remove(execution.operationId)
            execution.notificationClosure.completeExceptionally(error)
            if (!execution.closed) execution.cell?.let { sink.emit(NativeGrpcFrames.closed(session.localId, it)) }
        }
        callbacks.remove(session.localId)
        delegates.failed(session)
    }

    private fun acceptCell(execution: NativeGrpcExecutionRecord, cell: String) {
        if (cell.isEmpty() || cell.encodeToByteArray().size > NATIVE_GRPC_MAX_IDENTIFIER_BYTES) {
            throw IOException("Invalid code-mode cell ID")
        }
        check(execution.cell == null || execution.cell == cell) { "Code-mode execution changed its cell ID" }
        check(executions.values.none { it !== execution && it.session == execution.session && it.cell == cell }) {
            "code-mode host reused active cell ID $cell"
        }
        execution.cell = cell
    }

    private fun closeIfReady(execution: NativeGrpcExecutionRecord) {
        if (execution.cell == null) return
        val state = callbacks[execution.session.localId] ?: return
        val key = execution.session.localId to execution.executionId
        val notificationsSettled = state.notifications.values.none { it.execution == key }
        val completed = execution.closed && notificationsSettled
        val claimed = execution.started && execution.ready
        if (completed) execution.notificationClosure.complete(Unit)
        if (completed && claimed) {
            executions.remove(key)
            operations.remove(execution.operationId)
        }
    }
}
