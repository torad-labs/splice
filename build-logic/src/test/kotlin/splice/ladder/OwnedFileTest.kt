// The red-green proof for OwnedFile.release, the removal a gate leg runs before it writes its owned output. The defect it
// guards: File.delete() removes an EMPTY directory at the owned path, and a failed delete passes silently, so the leg
// then wrote its receipt into a place it had not cleared. Each case below runs on a real @TempDir.
package splice.ladder

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OwnedFileTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the owned file is removed, so a rerun writes its own`() {
        val owned = Files.writeString(dir.resolve("code-mode-mock.json"), "{}")
        OwnedFile(owned).release()
        assertFalse(Files.exists(owned))
    }

    @Test
    fun `an empty directory at the owned path is refused by name and survives`() {
        val owned = Files.createDirectories(dir.resolve("code-mode-mock.json"))
        val refused = assertThrows(IllegalArgumentException::class.java) { OwnedFile(owned).release() }
        assertTrue(refused.message!!.contains(owned.toString()))
        assertTrue(Files.isDirectory(owned))
    }

    @Test
    fun `a directory with contents at the owned path is refused and its contents survive`() {
        val owned = Files.createDirectories(dir.resolve("code-mode-mock.json"))
        val inside = Files.writeString(owned.resolve("kept.txt"), "kept")
        assertThrows(IllegalArgumentException::class.java) { OwnedFile(owned).release() }
        assertTrue(Files.exists(inside))
    }

    @Test
    fun `a missing owned path is nothing to remove`() {
        OwnedFile(dir.resolve("code-mode-mock.json")).release()
        assertFalse(Files.exists(dir.resolve("code-mode-mock.json")))
    }
}
