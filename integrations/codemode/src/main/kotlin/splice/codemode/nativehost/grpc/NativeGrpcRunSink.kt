package splice.codemode.nativehost.grpc

import kotlinx.serialization.json.JsonObject

internal interface NativeGrpcRunSink {
    fun emit(frame: JsonObject)
    fun invalidate(session: NativeGrpcSession, error: Throwable)
    suspend fun settle(session: NativeGrpcSession, cell: String, terminated: Boolean)
}
