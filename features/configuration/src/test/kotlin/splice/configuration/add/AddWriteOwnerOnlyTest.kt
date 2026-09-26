// NEW: V4-275 — `splice add` and the console's add rewrite splice.toml owner-only (0600) from the
// instant the new file exists. Before, the temp file renamed over it was created at the umask, so one
// add left splice.toml 0644 under the usual 022, extra_headers secrets included.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class AddWriteOwnerOnlyTest {

    @Test
    fun `an add rewrites splice toml owner-only, whatever the old file's mode - V4-275`(@TempDir tmp: Path) {
        if (!Files.getFileStore(tmp).supportsFileAttributeView("posix")) return
        val file = tmp.resolve("splice.toml")
        Files.writeString(file, "[heads.a]\nport = 8801\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))

        val written = AddWrite().replace(file, "[heads.a]\nport = 8801\n", "[heads.a]\nport = 8801\n\n[heads.b]\n")

        assertEquals(AddWritten.Written, written)
        assertEquals("[heads.a]\nport = 8801\n\n[heads.b]\n", Files.readString(file))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    // V4-279: the rename landed ON a linked splice.toml and replaced the link with a regular file.
    @Test
    fun `an add through a linked splice toml keeps the link and writes its target - V4-279`(@TempDir tmp: Path) {
        val target = Files.createDirectories(tmp.resolve("dotfiles")).resolve("splice.toml")
        Files.writeString(target, "[heads.a]\nport = 8801\n")
        val link = Files.createDirectories(tmp.resolve("config")).resolve("splice.toml")
        Files.createSymbolicLink(link, target)

        val written = AddWrite().replace(link, "[heads.a]\nport = 8801\n", "[heads.a]\nport = 8801\n\n[heads.b]\n")

        assertEquals(AddWritten.Written, written)
        assertTrue(Files.isSymbolicLink(link), "splice.toml is still the operator's link")
        assertEquals("[heads.a]\nport = 8801\n\n[heads.b]\n", Files.readString(target))
        if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
        }
    }
}
