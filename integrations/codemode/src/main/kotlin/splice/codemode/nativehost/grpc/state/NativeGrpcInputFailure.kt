package splice.codemode.nativehost.grpc.state

import java.io.IOException

/** A protocol rejection assembled only from serde's authored diagnosis, never its input bytes. */
internal class NativeGrpcInputFailure(val diagnostic: String, cause: IllegalArgumentException? = null) :
    IOException(diagnostic, cause)
