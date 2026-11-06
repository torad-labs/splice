// NEW: distinguishes local durable-state failures from upstream or JavaScript runtime failures.
package splice.provider.codex

import splice.core.failure.CodeModeStorageFault
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.memory.HeapCapacityException
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.upstream.codemode.CodeModeSourcePersistenceException
import java.io.IOException

internal class CodeModePersistenceException(
    cause: IOException,
    diskFull: Boolean,
    operation: CodeModeStorageOperation = CodeModeStorageOperation.SAVE,
) :
    CodeModeSourcePersistenceException(cause) {
    private val storage = when {
        cause is CodeModePersistenceException -> cause.storage
        cause is CodeModeStorageException -> cause.fault
        cause is HeapCapacityException -> CodeModeStorageFault.HEAP
        diskFull -> CodeModeStorageFault.DISK_FULL
        else -> CodeModeStorageFault.IO
    }
    private val detail = when (cause) {
        is CodeModePersistenceException -> cause.detail
        is CodeModeStorageException -> requireNotNull(cause.message)
        else -> when (storage) {
            CodeModeStorageFault.HEAP -> "the daemon heap reservation was refused"
            CodeModeStorageFault.DISK_FULL -> "the disk that holds splice's state is full, so free space on it"
            else -> "storage I/O failed; check state storage access and health"
        }
    }
    private val attempted = (cause as? CodeModePersistenceException)?.attempted ?: operation
    override val message: String =
        "code-mode state could not be ${attempted.description}: $detail; source was not rerun"

    fun outcome(): TurnOutcome.Failure = TurnOutcome.Failure(
        // A retry cannot repair a bounded or invalid journal. Temporary I/O retains its existing retry family.
        cause = FailureCause.Splice.Protocol(
            if (storage in DETERMINISTIC_STORAGE) ProtocolFault.CODE_MODE_STATE else ProtocolFault.CODE_MODE_START,
            storage,
        ),
        at = Position(Moment.MID_CONTENT, streamBegan = true),
        evidence = FailureEvidence(message),
        salvagedUsage = noRequestUsage,
    )
}

internal enum class CodeModeStorageOperation(val description: String) { SAVE("saved"), RESTORE("restored") }

private val DETERMINISTIC_STORAGE = setOf(
    CodeModeStorageFault.LIMIT,
    CodeModeStorageFault.INVALID,
    CodeModeStorageFault.ACCESS,
)
