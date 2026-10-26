package splice.codemode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import splice.codemode.nativehost.NativeCell
import splice.codemode.nativehost.NativeCellAdmission
import splice.codemode.nativehost.NativeCellLease
import splice.codemode.nativehost.NativeFrames
import splice.codemode.nativehost.NativePendingStart
import splice.codemode.nativehost.NativePreparedSession
import splice.codemode.nativehost.NativeRequests
import splice.codemode.nativehost.sessions.NativeLogicalCell
import splice.codemode.nativehost.sessions.NativeLogicalSessions
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.failure.CodeModeCapacityException
import splice.upstream.failure.CodeModeStartException
import splice.upstream.failure.CodeModeToolException
import java.io.IOException

/** Starts one module on the host as a cell of its logical session. A start that fails before dispatch gives its
 *  admission back at once; one that fails after dispatch asks the host to abandon the operation, and never
 *  authorizes running the source again. */
internal class NativeCellStarter(
    private val scope: CoroutineScope,
    private val admission: NativeCellAdmission,
    private val sessions: NativeLogicalSessions,
) {
    suspend fun start(
        sessionKey: String,
        source: String,
        definitions: List<CodeModeManual.NestedTool>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell {
        val prepared = beforeDispatch(sessionKey)
        val lease = prepared.lease
        var transferred = false
        var started: NativePendingStart? = null
        try {
            val host = prepared.host
            val sessionId = prepared.sessionId
            // A failure from this point can follow execution. It must never authorize a source retry.
            val operation = host.prepare(execute = true)
            started = NativePendingStart(host, sessionId, operation)
            host.events.expect(operation.id, sessionId, lease)
            host.dispatch(
                operation,
                NativeRequests.execute(
                    sessionId,
                    source,
                    definitions,
                    options,
                ),
            )
            val accepted = NativeFrames.value(operation.response.await())
            val cellId = JsonScalars.str(accepted, "cellId") ?: throw IOException("Native host did not accept a cell")
            val raw = NativeCell(cellId, sessionId, host, scope, operation, definitions, lease)
            return NativeLogicalCell(raw, prepared.owner, prepared.binding, cellId).also {
                transferred = true
            }
        } finally {
            if (!transferred) abandon(started, lease)
        }
    }

    private fun abandon(started: NativePendingStart?, lease: NativeCellLease) {
        if (started == null || !started.operation.wasDispatched()) {
            started?.let { it.host.discard(it.operation) }
            lease.close()
        } else {
            scope.launch {
                try {
                    Cancellables.runCatchingBestEffort {
                        NativeFrames.value(started.operation.response.await())
                        started.host.send(NativeRequests.control("operation/abandon", started.operation.id))
                    }
                } finally {
                    lease.close()
                }
            }
        }
    }

    private suspend fun beforeDispatch(sessionKey: String): NativePreparedSession = try {
        val owner = sessions.session(sessionKey)
        val binding = owner.request()
        NativePreparedSession(owner, binding, admission.reserve())
    } catch (error: CodeModeToolException) {
        throw error
    } catch (error: CodeModeCapacityException) {
        throw error
    } catch (error: IOException) {
        throw CodeModeStartException(error)
    }
}
