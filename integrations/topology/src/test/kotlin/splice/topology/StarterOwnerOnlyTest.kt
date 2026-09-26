// NEW: V4-275 — the first-run splice.toml is created owner-only (0600). The starter holds no secret,
// but it is the file the operator then edits by hand, extra_headers included, and an editor keeps the
// mode it finds: created at the umask, it stayed 0644 under the usual 022 for good.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class StarterOwnerOnlyTest {

    @Test
    fun `the first-run splice toml is created owner-only - V4-275`(@TempDir tmp: Path) {
        if (!Files.getFileStore(tmp).supportsFileAttributeView("posix")) return
        val file = tmp.resolve("splice.toml")

        val _ = TopologyLoader.loadOrMaterializeWithDigest(file)

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }
}
