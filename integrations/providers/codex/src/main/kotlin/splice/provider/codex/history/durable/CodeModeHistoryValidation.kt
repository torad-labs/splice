package splice.provider.codex.history.durable

import kotlinx.serialization.SerializationException
import splice.core.memory.HeapCapacityException
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeStorageException
import splice.provider.codex.CodeModeStorageOperation
import splice.provider.codex.CodeModeStorageProblem
import java.io.IOException

/** History boundary failures retain their persistence category and source cause. */
internal object CodeModeHistoryValidation {
    fun legacy(state: CodeModeHistoryState, key: String) {
        require(state.key == key && state.version == 1) { "Legacy authoritative history identity is invalid" }
    }

    inline fun <T> guarded(kind: CodeModeStorageOperation, operation: () -> T): T = try {
        operation()
    } catch (error: CodeModePersistenceException) {
        throw error
    } catch (error: HeapCapacityException) {
        // Capacity refusal leaves the committed revision intact and is retryable when the reservation is available.
        throw error
    } catch (error: SerializationException) {
        throw CodeModePersistenceException(
            CodeModeStorageException.Invalid(CodeModeStorageProblem.DECODE, error),
            false,
            kind,
        )
    } catch (error: IllegalArgumentException) {
        throw CodeModePersistenceException(
            CodeModeStorageException.Invalid(CodeModeStorageProblem.INVALID_REVISION, error),
            false,
            kind,
        )
    } catch (error: IOException) {
        throw CodeModePersistenceException(error, false, kind)
    }
}
