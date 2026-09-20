// NEW: V4-129 — WrapStateStore's own round trip and its absence/corruption reads, isolated from the
// higher-level wrap()/unwrap() orchestration in WrappedHeadTest.
package console.v4129

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.launch.WrapState
import splice.core.launch.WrapStateStore
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
        val store = WrapStateStore(file = home.resolve("nested/claude-head-wrap.json"))
        assertNull(store.read())
    }

    @Test
    fun `write then read round-trips every field, at owner-only permissions`(@TempDir home: Path) {
        val file = home.resolve("claude-head-wrap.json")
        val store = WrapStateStore(file = file)
        store.write(state)
        assertEquals(state, store.read())
        val perms = Files.getPosixFilePermissions(file)
        assertEquals(setOf(OWNER_READ, OWNER_WRITE), perms)
    }

    @Test
    fun `clear removes the file so a later read answers absent again`(@TempDir home: Path) {
        val file = home.resolve("claude-head-wrap.json")
        val store = WrapStateStore(file = file)
        store.write(state)
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun `a corrupted file reads as absent rather than throwing`(@TempDir home: Path) {
        val file = home.resolve("claude-head-wrap.json")
        file.writeText("not json at all {{{")
        val store = WrapStateStore(file = file)
        assertNull(store.read())
    }
}
