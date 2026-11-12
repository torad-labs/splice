package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage

/** The opaque routing token belongs to this session, even when sessions share one channel. */
internal data class NativeGrpcSession(val localId: String, val id: String, val route: String?)

internal interface NativeGrpcSessionSink {
    fun event(session: NativeGrpcSession, message: DynamicMessage)
    fun call(session: NativeGrpcSession, message: DynamicMessage)
    fun failed(session: NativeGrpcSession, error: Throwable)
}
