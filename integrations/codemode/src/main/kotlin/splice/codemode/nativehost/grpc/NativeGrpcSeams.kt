package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage

/** A broken byte connection invalidates the process and every session using it. */
internal fun interface NativeGrpcTransportFailure {
    operator fun invoke(error: Throwable)
}

/** A failed lease or subscription invalidates only its owning session. */
internal fun interface NativeGrpcSessionFailure {
    operator fun invoke(error: Throwable)
}

/** Routes ordered session lease events without coupling them to tool subscription delivery. */
internal fun interface NativeGrpcSessionEvent {
    operator fun invoke(session: NativeGrpcSession, message: DynamicMessage)
}
