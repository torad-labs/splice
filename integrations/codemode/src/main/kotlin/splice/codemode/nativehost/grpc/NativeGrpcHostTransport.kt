package splice.codemode.nativehost.grpc

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import splice.codemode.nativehost.NativeConnection
import splice.codemode.nativehost.NativeOperation
import splice.upstream.failure.CODE_MODE_SESSION_CLOSED_ERROR
import splice.upstream.failure.CodeModeToolException

/** The production transport to the unmodified official current Codex host. */
internal class NativeGrpcHostTransport(
    process: Process,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val connection: NativeConnection,
) : AutoCloseable {
    private val channel = NativeGrpcChannel(process, scope, ioDispatcher, connection::fail)
    private val router = NativeGrpcRouter(channel, scope, connection)

    fun start() = channel.start()

    suspend fun send(frame: JsonObject, operation: NativeOperation?) = withContext(ioDispatcher) {
        if (!connection.isAvailable()) throw CodeModeToolException(CODE_MODE_SESSION_CLOSED_ERROR)
        router.operations.send(frame, operation)
    }

    fun hasSession(id: String): Boolean = router.sessions.get(id) != null

    fun cancelObservation(operation: Long) = router.cancelObservation(operation)

    fun afterExit(action: Runnable) = channel.afterExit(action)

    fun isAvailable(): Boolean = channel.isAvailable()

    override fun close() {
        router.sessions.close()
        channel.close()
    }
}
