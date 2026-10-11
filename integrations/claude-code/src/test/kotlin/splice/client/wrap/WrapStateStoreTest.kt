// NEW: V4-129 — WrapStateStore's own round trip and its absence/corruption reads, isolated from the
// higher-level wrap()/unwrap() orchestration in WrappedHeadTest.
package splice.client.wrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import kotlin.io.path.writeText

class WrapStateStoreTest {

    private val state = WrapState(
        realBinaryPath = "/home/op/.local/share/claude/versions/2.1.278",
        shadowedSymlinkTarget = "/home/op/.local/share/claude/versions/2.1.278",
        shimPath = "/home/op/.local/share/splice/splice-launch",
        settingsBackupPath = "/home/op/.claude/settings.json.splice-wrap-backup-1",
        claudeJsonBackupPath = "/home/op/.claude/.claude.json.splice-wrap-backup-1",
        wrappedAtEpochMillis = 12_345L,
    )

    @Test
    fun `a never-written store reads absent, never a crash`(@TempDir home: Path) {
        val file = home.resolve("nested/claude-head-wrap.json")
        assertEquals(StoredWrap.Absent(file), WrapStateStore(file = file).read())
    }

    @Test
    fun `write then read round-trips every field, at owner-only permissions`(@TempDir home: Path) {
        val file = home.resolve("claude-head-wrap.json")
        val store = WrapStateStore(file = file)
        store.write(state)
        assertEquals(StoredWrap.Present(state), store.read())
        val perms = Files.getPosixFilePermissions(file)
        assertEquals(setOf(OWNER_READ, OWNER_WRITE), perms)
    }

    @Test
    fun `clear removes the file so a later read answers absent again`(@TempDir home: Path) {
        val file = home.resolve("claude-head-wrap.json")
        val store = WrapStateStore(file = file)
        store.write(state)
        store.clear()
        assertEquals(StoredWrap.Absent(file), store.read())
    }

    @Test
    fun `a file that is there and cannot be used is unreadable, never absent, and names itself`(@TempDir home: Path) {
        val file = home.resolve("claude-head-wrap.json")
        val store = WrapStateStore(file = file)
        listOf("not json at all {{{", "[]", "\"text\"", "{}", """{"shim_path":"/x"}""").forEach { body ->
            file.writeText(body)
            val read = store.read()
            assertTrue(read is StoredWrap.Unreadable, "$body read as $read")
            assertEquals(null, read.state)
            val problem = read.problem().orEmpty()
            assertTrue(problem.contains(file.toString()), "the message names the file: $problem")
        }
    }
}
