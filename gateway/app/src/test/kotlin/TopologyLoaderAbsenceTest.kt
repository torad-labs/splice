// DR-66 absence-class arms for TopologyLoader.loadOrMaterialize: the exists() pre-gate plus a
// truncating write treated everything unseen as first-run — a dangling dotfiles symlink got the
// starter written THROUGH it into the operator's target, an existing splice.toml behind a denied
// parent was one recovered access away from a starter clobber, and a concurrent creator between
// check and write lost its file. CREATE_NEW closes the race by construction; these arms pin the
// two deterministic faces.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class TopologyLoaderAbsenceTest {

    @Test
    fun `a dangling splice-toml symlink aborts loud, never materialized through - DR-66`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("dotfiles")) // target dir exists; the file is not synced yet
        val target = tmp.resolve("dotfiles").resolve("splice.toml")
        val link = tmp.resolve("splice.toml")
        Files.createSymbolicLink(link, target)

        assertThrows(IOException::class.java) { TopologyLoader.loadOrMaterializeWithDigest(link) }

        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS), "starter must not be written through the link")
        assertTrue(Files.isSymbolicLink(link), "the operator's link survives")
    }

    @Test
    fun `an existing splice-toml behind a denied parent aborts intact - DR-66`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("cfg"))
        val file = dir.resolve("splice.toml")
        val precious = "# operator topology - must survive\n"
        Files.writeString(file, precious)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"))
        try {
            assertThrows(IOException::class.java) { TopologyLoader.loadOrMaterializeWithDigest(file) }
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals(precious, Files.readString(file), "an unreadable existing topology is never clobbered")
    }

    @Test
    fun `genuine absence still materializes the starter - DR-66 control`(@TempDir tmp: Path) {
        val file = tmp.resolve("fresh").resolve("splice.toml")
        val loaded = TopologyLoader.loadOrMaterializeWithDigest(file)
        assertTrue(Files.exists(file), "starter created on true first run")
        assertTrue(loaded.digest.isNotEmpty())
    }
}
