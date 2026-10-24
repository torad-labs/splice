package splice.provider.codex.history.durable

import kotlinx.serialization.SerializationException
import splice.provider.codex.CodeModePersistenceException
import java.io.IOException

/** History boundary failures retain their persistence category and source cause. */
internal object CodeModeHistoryValidation {
    fun legacy(state: CodeModeHistoryState, key: String) {
        require(state.key == key && state.version == 1) { "Legacy authoritative history identity is invalid" }
    }

    inline fun <T> guarded(operation: () -> T): T = try {
        operation()
    } catch (error: SerializationException) {
        throw CodeModePersistenceException(IOException("authoritative history cannot be decoded", error), false)
    } catch (error: IllegalArgumentException) {
        throw CodeModePersistenceException(IOException("authoritative history revision is invalid", error), false)
    } catch (error: IOException) {
        throw CodeModePersistenceException(error, false)
    }
}
