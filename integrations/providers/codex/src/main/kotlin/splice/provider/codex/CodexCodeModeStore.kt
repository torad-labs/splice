// NEW: persists bounded code-mode records and expiry markers through atomic 0600 writes.
// CREDENTIAL-WRITE-EXEMPT[2026-09-21]: code-mode batch state, never a credential. This file
// persists the code-mode store's own record of admissions, completions and expiry history; it
// holds no token, and a from-scratch write loses nothing another process wrote beside it.
package splice.provider.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class CodexCodeModeStore(
    private val stateFile: Path,
    private val json: Json,
) {
    private var needsSave = false

    fun load(): CodeModePersistedState {
        if (!Files.exists(stateFile)) return CodeModePersistedState()
        return json.decodeFromString(Files.readString(stateFile))
    }

    fun save(records: List<CodeModeRecord>, expired: List<CodeModeExpiredSnapshot>, retryOnly: Boolean = false) {
        if (retryOnly && !needsSave) return
        needsSave = true
        val state = CodeModePersistedState(
            records = records.map(CodeModeRecord::snapshot),
            expired = expired.toList(),
        )
        try {
            SecureFile.writeAtomic0600(stateFile, json.encodeToString(state))
            needsSave = false
        } catch (error: IOException) {
            throw CodeModePersistenceException(error)
        }
    }
}
