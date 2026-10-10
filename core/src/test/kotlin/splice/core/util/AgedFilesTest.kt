// NEW: Oct 10, 2026 — AgedFiles counts and removes what a store holds from before a moment, by last write.
package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class AgedFilesTest {

    @TempDir
    lateinit var dir: Path

    private fun file(name: String, bytes: Int, writtenMs: Long): Path {
        val path = dir.resolve(name)
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(bytes))
        Files.setLastModifiedTime(path, FileTime.fromMillis(writtenMs))
        return path
    }

    @Test
    fun `only files last written before the moment are counted and removed, nested ones included`() {
        val old = file("a/old.jsonl", 10, 1_000)
        val nested = file("a/b/older.jsonl", 5, 500)
        val fresh = file("fresh.jsonl", 7, 5_000)
        val aged = AgedFiles(dir)

        assertEquals(15L, aged.bytesBefore(2_000))
        assertEquals(15L, aged.deleteBefore(2_000), "it frees what it counted")
        assertTrue(Files.notExists(old) && Files.notExists(nested))
        assertTrue(Files.exists(fresh))
        assertEquals(0L, aged.bytesBefore(2_000))
    }

    @Test
    fun `a directory that was never written is zero bytes, not a failure`() {
        val aged = AgedFiles(dir.resolve("never"))
        assertEquals(0L, aged.bytesBefore(Long.MAX_VALUE))
        assertEquals(0L, aged.deleteBefore(Long.MAX_VALUE))
    }

    @Test
    fun `dotfiles and symbolic links are left alone`() {
        val lock = file(".lock", 3, 1)
        val target = file("outside/target.bin", 9, 1)
        val inside = dir.resolve("store").also { Files.createDirectories(it) }
        Files.createSymbolicLink(inside.resolve("link"), target)

        assertEquals(0L, AgedFiles(inside).deleteBefore(Long.MAX_VALUE))
        assertTrue(Files.exists(lock) && Files.exists(target))
    }

    @Test
    fun `a stamp other than the last write decides what is old`() {
        file("x", 4, 9_000)
        val aged = AgedFiles(dir) { 1L }
        assertEquals(4L, aged.bytesBefore(2))
    }
}
