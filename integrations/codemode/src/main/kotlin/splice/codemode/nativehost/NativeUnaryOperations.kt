package splice.codemode.nativehost

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** A dropped unary call cancels its RPC, including cancellation while dispatch returns to its caller. */
internal object NativeUnaryOperations {
    suspend fun response(connection: NativeConnection, request: JsonObject): JsonObject {
        val operation = connection.prepare()
        return try {
            connection.dispatch(operation, request)
            operation.response.await()
        } catch (cancelled: CancellationException) {
            if (operation.wasDispatched()) operation.response.cancel(cancelled)
            throw cancelled
        } finally {
            if (!operation.wasDispatched()) connection.discard(operation)
        }
    }
}
