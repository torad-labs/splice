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

/** The free space on the disk that holds a path, null when it cannot be read. A failed save asks it
 *  so the turn's message can say the disk is full (V4-397). */
internal fun interface StateDiskSpace {
    operator fun invoke(file: Path): Long?

    /** The usable bytes on the file store of the nearest existing ancestor; null when there is none
     *  or the store cannot be read, which never reads as a full disk. */
    object Usable : StateDiskSpace {
        override fun invoke(file: Path): Long? {
            val existing = generateSequence(file.toAbsolutePath()) { it.parent }.firstOrNull { Files.exists(it) }
            return try {
                existing?.let { Files.getFileStore(it).usableSpace }
            } catch (_: IOException) {
                null
            }
        }
    }
}

internal class CodexCodeModeStore(
    private val stateFile: Path,
    private val json: Json,
    private val freeBytes: StateDiskSpace = StateDiskSpace.Usable,
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
        val encoded = json.encodeToString(state)
        try {
            SecureFile.writeAtomic0600(stateFile, encoded)
            needsSave = false
        } catch (error: IOException) {
            val free = freeBytes(stateFile)
            throw CodeModePersistenceException(error, diskFull = free != null && free < encoded.toByteArray().size)
        }
    }
}
