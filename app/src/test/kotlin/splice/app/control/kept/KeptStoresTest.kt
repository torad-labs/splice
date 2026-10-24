// The two kept stores Settings > Your data counts and clears: code mode work and parked compaction summaries.
// Each is counted, cleared and counted again, on files written the way their stores write them.
package splice.app.control.kept

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.CODE_MODE_DIR
import splice.core.config.CODE_MODE_STATE_SUFFIX
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class KeptStoresTest {
    private fun write(file: Path, text: String, modifiedMs: Long): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedMs))
        return file
    }

    @Test
    fun `code mode work is counted across heads, including a head no longer configured, then cleared`(
        @TempDir tmp: Path,
    ) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        write(paths.headsDir.resolve("claudex").resolve(CODE_MODE_DIR).resolve("a.json"), "12345", 2_000)
        write(paths.headsDir.resolve("claudex").resolve(CODE_MODE_DIR).resolve("b.json"), "123", 5_000)
        write(paths.headsDir.resolve("gone-head").resolve(CODE_MODE_DIR).resolve("c.json"), "12", 3_000)
        write(paths.stateDir.resolve("claudex$CODE_MODE_STATE_SUFFIX"), "1234", 1_000)
        // Not code mode work: the same head's other kept store must be left alone.
        val reasoning = write(paths.headsDir.resolve("claudex").resolve("reasoning").resolve("r.json"), "zz", 1_000)
        val store = CodeModeKept(paths)

        assertEquals(StoreHeld(files = 4, bytes = 14, oldestMs = 1_000), store.held())
        assertNull(store.clear())
        assertEquals(StoreHeld(files = 0, bytes = 0, oldestMs = null), store.held())
        assertTrue(Files.exists(reasoning), "clearing code mode work must not touch reasoning")
    }

    @Test
    fun `a install that never used code mode holds nothing and clears without a failure`(@TempDir tmp: Path) {
        val store = CodeModeKept(StatePaths(baseOverride = tmp.resolve("state")))

        assertEquals(StoreHeld(0, 0, null), store.held())
        assertNull(store.clear())
    }

    @Test
    fun `parked compaction summaries are counted per head, then cleared`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        write(paths.compactionRecordingsDir("claudex").resolve("one.json"), "abcd", 4_000)
        write(paths.compactionRecordingsDir("claudex").resolve("two.json"), "ef", 9_000)
        write(paths.compactionRecordingsDir("gone-head").resolve("three.json"), "ghi", 6_000)
        val store = CompactionSummariesKept(paths)

        assertEquals(StoreHeld(files = 3, bytes = 9, oldestMs = 4_000), store.held())
        assertNull(store.clear())
        assertEquals(StoreHeld(files = 0, bytes = 0, oldestMs = null), store.held())
    }

    @Test
    fun `a store that cannot be read says so instead of counting part of it`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val dir = paths.compactionRecordingsDir("claudex")
        write(dir.resolve("one.json"), "abcd", 1_000)
        Files.setPosixFilePermissions(dir, emptySet())
        try {
            val store = CompactionSummariesKept(paths)
            val failure = runCatching { store.held() }.exceptionOrNull()
            assertTrue(failure != null, "an unreadable directory must throw, not count zero")
            assertTrue(store.clear()?.startsWith("compaction summaries:") == true)
        } finally {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }
}
