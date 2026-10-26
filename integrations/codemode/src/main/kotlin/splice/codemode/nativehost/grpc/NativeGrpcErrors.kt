package splice.codemode.nativehost.grpc

import io.grpc.Status
import io.grpc.StatusRuntimeException
import splice.core.util.SafeFailureText
import splice.upstream.codemode.json.CodeModeDiagnosticText
import splice.upstream.failure.CodeModeToolException
import java.io.IOException

// PORT-OF: deadline.rs failure truncates the complete UTF-8 diagnostic at 512 bytes, including the suffix.
private const val NATIVE_GRPC_ERROR_MAX_BYTES = 512
private const val NATIVE_GRPC_ERROR_SUFFIX = "..."

// why: A UTF-8 continuation byte starts with binary10; truncation backs up to the code-point boundary.
private const val NATIVE_UTF8_PREFIX_MASK = 0xc0

// why: a UTF-8 continuation byte is 10xxxxxx (RFC 3629 section 3), so it reads 0x80 under the 0xc0 mask.
private const val NATIVE_UTF8_CONTINUATION_PREFIX = 0x80

/** Only native RPC statuses and explicitly authored API diagnostics bypass throwable-message redaction. */
internal object NativeGrpcErrors {
    // PORT-OF: tonic 0.14.3 status.rs Code::description; the pinned Rust client displays these protocol values.
    private val descriptions = mapOf(
        Status.Code.OK to "The operation completed successfully",
        Status.Code.CANCELLED to "The operation was cancelled",
        Status.Code.UNKNOWN to "Unknown error",
        Status.Code.INVALID_ARGUMENT to "Client specified an invalid argument",
        Status.Code.DEADLINE_EXCEEDED to "Deadline expired before operation could complete",
        Status.Code.NOT_FOUND to "Some requested entity was not found",
        Status.Code.ALREADY_EXISTS to "Some entity that we attempted to create already exists",
        Status.Code.PERMISSION_DENIED to "The caller does not have permission to execute the specified operation",
        Status.Code.RESOURCE_EXHAUSTED to "Some resource has been exhausted",
        Status.Code.FAILED_PRECONDITION to "The system is not in a state required for the operation's execution",
        Status.Code.ABORTED to "The operation was aborted",
        Status.Code.OUT_OF_RANGE to "Operation was attempted past the valid range",
        Status.Code.UNIMPLEMENTED to "Operation is not implemented or not supported",
        Status.Code.INTERNAL to "Internal error",
        Status.Code.UNAVAILABLE to "The service is currently unavailable",
        Status.Code.DATA_LOSS to "Unrecoverable data loss or corruption",
        Status.Code.UNAUTHENTICATED to "The request does not have valid authentication credentials",
    )

    fun render(error: Throwable, operation: String): String = when (error) {
        is CodeModeToolException -> error.diagnostic
        is NativeGrpcProtocolException -> error.diagnostic
        is StatusRuntimeException -> status(error, operation)
        else -> SafeFailureText.render(error)
    }

    fun invalidate(sink: NativeGrpcRunSink, session: NativeGrpcSession?, error: StatusRuntimeException) {
        if (session != null && NativeGrpcDeadlines.invalidatesSession(error, session.route != null)) {
            sink.invalidate(session, error)
        }
    }

    fun invalidate(sink: NativeGrpcRunSink, session: NativeGrpcSession?, error: NativeGrpcProtocolException) {
        if (session != null && error.invalidatesSession) sink.invalidate(session, error)
    }

    private fun status(error: StatusRuntimeException, operation: String): String {
        val status = error.status
        val message = status.description.orEmpty()
        val detail = if (message.isEmpty()) "" else ", message: ${CodeModeDiagnosticText.quoted(message)}"
        return bounded("gRPC code-mode $operation failed: code: '${descriptions.getValue(status.code)}'$detail")
    }

    private fun bounded(message: String): String {
        val bytes = message.encodeToByteArray()
        if (bytes.size <= NATIVE_GRPC_ERROR_MAX_BYTES) return message
        var boundary = NATIVE_GRPC_ERROR_MAX_BYTES - NATIVE_GRPC_ERROR_SUFFIX.length
        while (bytes[boundary].toInt() and NATIVE_UTF8_PREFIX_MASK == NATIVE_UTF8_CONTINUATION_PREFIX) boundary--
        return bytes.decodeToString(0, boundary) + NATIVE_GRPC_ERROR_SUFFIX
    }
}

/** Source-defined conversion errors leave the session alive unless ownership was contradicted. */
internal class NativeGrpcProtocolException(
    val diagnostic: String,
    val invalidatesSession: Boolean = false,
    cause: Throwable? = null,
) : IOException(diagnostic, cause)
