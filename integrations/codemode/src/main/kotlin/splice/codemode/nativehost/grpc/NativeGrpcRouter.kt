package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import splice.codemode.nativehost.NativeConnection
import splice.upstream.failure.CODE_MODE_SESSION_CLOSED_ERROR
import splice.upstream.failure.CodeModeToolException
import java.io.IOException

/** Keeps lease events, callbacks and observations independent while sharing one admitted session identity. */
internal class NativeGrpcRouter(
    channel: NativeGrpcChannel,
    scope: CoroutineScope,
    private val connection: NativeConnection,
) : NativeGrpcSessionSink, NativeGrpcDelegateSink, NativeGrpcRunSink {
    val sessions = NativeGrpcSessions(channel, scope, this, tracing = connection.tracing)
    private val delegates = NativeGrpcDelegates(channel, scope, this, connection.tracing)
    private val executions = NativeGrpcExecutions(delegates, this)
    private val running = NativeGrpcExecutionsRun(channel, scope, executions, this, connection.tracing)
    private val waits = NativeGrpcWaits(channel, scope, this)
    private val controls = NativeGrpcControls(channel, running, waits, executions, this)
    val operations = NativeGrpcOperationsBridge(channel, scope, sessions, controls, delegates, this)

    fun cancelObservation(operation: Long) = waits.cancelObservation(operation)

    override fun emit(frame: JsonObject) {
        try {
            connection.receive(frame)
        } catch (error: IOException) {
            connection.fail(error)
        }
    }

    override fun event(session: NativeGrpcSession, message: DynamicMessage) = guarded(session) {
        when {
            NativeGrpcFields.optional(message, "tool_call_cancelled") != null -> executions.cancelled(
                session,
                NativeGrpcFields.identifier(NativeGrpcFields.message(message, "tool_call_cancelled"), "invocation_id"),
            )
            NativeGrpcFields.optional(message, "notification") != null -> executions.notification(
                session,
                NativeGrpcFields.message(message, "notification"),
            )
            // Current official callbacks.rs deliberately ignores this informational event.
            NativeGrpcFields.optional(message, "notification_cancelled") != null -> Unit
            NativeGrpcFields.optional(message, "cell_closed") != null -> executions.closed(
                session,
                NativeGrpcFields.message(message, "cell_closed"),
            )
            else -> throw IOException("Unexpected CodeMode lease event")
        }
    }

    override fun call(session: NativeGrpcSession, message: DynamicMessage) = guarded(session) {
        executions.call(session, message)
    }

    override fun failed(session: NativeGrpcSession, error: Throwable) {
        if (sessions.get(session.localId) === session) {
            sessions.invalidate(session.localId, error)
        } else {
            val closed = CodeModeToolException(CODE_MODE_SESSION_CLOSED_ERROR, error)
            running.failed(session, closed)
            waits.failed(session, closed)
            executions.failed(session, error)
            connection.failSession(session.localId, error)
        }
    }

    override fun invalidate(session: NativeGrpcSession, error: Throwable) {
        if (sessions.get(
                session.localId,
            ) === session
        ) {
            sessions.invalidate(session.localId, error)
        } else {
            failed(session, error)
        }
    }

    override fun settled(session: NativeGrpcSession, cell: String, id: String) = guarded(session) {
        executions.settled(session, cell, id)
    }

    override suspend fun settle(session: NativeGrpcSession, cell: String, terminated: Boolean) {
        if (terminated) {
            delegates.terminateNotifications(
                session,
                cell,
            )
        } else {
            executions.awaitNotifications(session, cell)
        }
    }

    private fun guarded(session: NativeGrpcSession, action: NativeGrpcProtocolEvent) {
        try {
            action.deliver()
        } catch (error: IOException) {
            invalidate(session, error)
        } catch (error: IllegalArgumentException) {
            invalidate(session, error)
        } catch (error: IllegalStateException) {
            invalidate(session, error)
        }
    }
}

internal fun interface NativeGrpcProtocolEvent {
    fun deliver()
}
