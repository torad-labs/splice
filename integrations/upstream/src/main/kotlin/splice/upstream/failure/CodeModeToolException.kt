package splice.upstream.failure

import java.io.IOException

/** An authored CodeMode API diagnostic belongs to the model's tool result, never an automatic source retry. */
public class CodeModeToolException(
    public val diagnostic: String,
    cause: Throwable? = null,
    /** A failed wait can leave its original cell running; it still belongs to the logical session. */
    public val preserveCell: Boolean = false,
) :
    IOException(diagnostic, cause)

// PORT-OF: code-mode/src/grpc_session/deadline.rs session_request cancellation.
public const val CODE_MODE_SESSION_CLOSED_ERROR: String = "gRPC code-mode session closed"

// PORT-OF: code-mode/src/grpc_session/mod.rs SHUTDOWN_ERROR.
public const val CODE_MODE_SESSION_SHUTDOWN_ERROR: String = "code mode session is shutting down"

// PORT-OF: code-mode/src/grpc_session/reconnect.rs binding_for_request.
public const val CODE_MODE_RUNTIME_REPLACED_ERROR: String =
    "The exec runtime was replaced. Stored values and running cells were lost. " +
        "Prior external tool operations may have completed; inspect their results before retrying. " +
        "You may continue with a fresh exec call."
