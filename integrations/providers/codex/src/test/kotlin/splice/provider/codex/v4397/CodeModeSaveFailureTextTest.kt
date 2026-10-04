package splice.provider.codex.v4397

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.save.StateDiskSpace
import java.nio.file.Files
import java.nio.file.Path

/** V4-397: a code-mode save that fails says why. On Sep 28 the root disk was full from 10:57 to
 *  11:03 AM CT, and every claudex save read only "code-mode state persistence failed", which the
 *  operator read as a code-mode bug. */
class CodeModeSaveFailureTextTest {
    private val json = Json

    /** A state directory under a regular file: the write fails with an IOException on any disk. */
    private fun unwritable(dir: Path): Path {
        val blocker = dir.resolve("not-a-dir")
        Files.writeString(blocker, "")
        return blocker.resolve("code-mode")
    }

    private fun storeOn(dir: Path, freeBytes: StateDiskSpace) = CodexCodeModeStore(
        CodeModeStateLocation(unwritable(dir), dir.resolve("legacy.json")),
        json,
        LogSink { },
        freeBytes,
    )

    private val oneRecord = listOf(CodeModeRecords.of("conversation-1", 1))

    @Test
    fun `a save on a full disk names the full disk and keeps the not-rerun guarantee`(@TempDir dir: Path) {
        val store = storeOn(dir) { 0L }

        val failure = assertThrows<CodeModePersistenceException> { store.save(oneRecord, emptyList()) }

        val text = failure.outcome().message
        assertTrue(text.contains("disk") && text.contains("full"), text)
        assertTrue(text.contains("source was not rerun"), text)
    }

    @Test
    fun `a save that fails with room left names its cause, not a full disk`(@TempDir dir: Path) {
        val store = storeOn(dir) { Long.MAX_VALUE }

        val failure = assertThrows<CodeModePersistenceException> { store.save(oneRecord, emptyList()) }

        val text = failure.outcome().message
        assertFalse(text.contains("full"), text)
        assertTrue(text.contains("not-a-dir"), text)
        assertTrue(text.contains("source was not rerun"), text)
    }

    @Test
    fun `an unreadable free-space answer never claims a full disk`(@TempDir dir: Path) {
        val store = storeOn(dir) { null }

        val failure = assertThrows<CodeModePersistenceException> { store.save(oneRecord, emptyList()) }

        assertFalse(failure.outcome().message.contains("full"), failure.outcome().message)
    }
}
