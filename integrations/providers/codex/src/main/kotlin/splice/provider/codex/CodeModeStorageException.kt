package splice.provider.codex

import splice.core.failure.CodeModeStorageFault
import java.io.IOException

/** Only controlled descriptions and byte counts are safe to carry across the persistence boundary. */
internal sealed class CodeModeStorageException(
    val fault: CodeModeStorageFault,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    class Limit(val bytes: Long, val maxBytes: Long) : CodeModeStorageException(
        CodeModeStorageFault.LIMIT,
        "authoritative history journal exceeds its byte bound (required=$bytes bytes, limit=$maxBytes bytes)",
    )

    class Invalid(problem: CodeModeStorageProblem, cause: Throwable? = null) : CodeModeStorageException(
        CodeModeStorageFault.INVALID,
        problem.description,
        cause,
    )

    class Access : CodeModeStorageException(
        CodeModeStorageFault.ACCESS,
        "the code-mode state directory is not owner-only",
    )
}

internal enum class CodeModeStorageProblem(val description: String) {
    NO_INITIAL_REVISION("authoritative history has no complete initial revision"),
    REVISION_IDENTITY("authoritative history revision identity is invalid"),
    MISSING_PARENT("authoritative history parent is absent"),
    DECODE("saved code-mode history cannot be decoded"),
    INVALID_REVISION("saved code-mode history revision is invalid"),
    COLD_STATE("cold code-mode state is invalid"),
}
