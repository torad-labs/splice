// NEW: v0.4.0 review — SecureFile.ownerOnlyDirectory's promise is a MODE, so it is checked on the mode
// the directory ends up with. It discarded every chmod failure as "POSIX perms unsupported", which a
// Linux filesystem never is: what it swallowed was a refusal, and the directory stayed open unsaid.
package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class SecureFileTest {

    @Test
    fun `an open directory is tightened and nothing is reported`(@TempDir tmp: Path) {
        val open = Files.createDirectories(tmp.resolve("open"))
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxr-x"))

        assertNull(SecureFile.ownerOnlyDirectory(open))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(open)))
    }

    // procfs refuses a mode change on a pid directory to every caller, root included (proc_setattr
    // answers EPERM for ATTR_MODE), so this is a refused chmod on any Linux box without touching a
    // directory anything else relies on.
    @Test
    fun `a refused chmod is reported, not swallowed`() {
        val procSelf = Path.of("/proc/self")
        assumeTrue(Files.isDirectory(procSelf), "procfs is Linux-only")

        val reason = SecureFile.ownerOnlyDirectory(procSelf)

        assertTrue(reason?.isNotBlank() == true, "the directory is not owner-only and the caller must be told")
    }
}
