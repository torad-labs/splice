// NEW: v0.4.0 review — SecureFile.ownerOnlyDirectory's promise is a MODE, so it is checked on the mode
// the directory ends up with. It discarded every chmod failure as "POSIX perms unsupported", which a
// Linux filesystem never is: what it swallowed was a refusal, and the directory stayed open unsaid.
package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
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

    // v0.4.0 review round 2: a filesystem that keeps no POSIX modes has nothing to hold, so the answer is
    // null, as the KDoc promises. The branch that said so sat inside runCatchingCancellable's getOrElse,
    // which never sees an UnsupportedOperationException — it passes through — so it threw instead.
    // zipfs without enablePosixFileAttributes is such a filesystem, and ships with every JDK.
    @Test
    fun `a filesystem without POSIX modes answers null, never throws`(@TempDir tmp: Path) {
        val zip = URI.create("jar:${tmp.resolve("state.zip").toUri()}")
        FileSystems.newFileSystem(zip, mapOf("create" to "true")).use { fs ->
            val logs = fs.getPath("/state/logs")

            assertNull(SecureFile.ownerOnlyDirectory(logs))
            assertTrue(Files.isDirectory(logs), "the directory is still made")
        }
    }

    // V4-275: the first-run splice.toml's primitive. Owner-only from the instant the file exists, and
    // exclusive: it never writes over, or through, anything already at the path.
    @Test
    fun `createNew0600 makes the file owner-only and never writes over one already there`(@TempDir tmp: Path) {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"), "POSIX modes")
        val file = tmp.resolve("splice.toml")

        SecureFile.createNew0600(file, "[daemon]\n".toByteArray())

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("[daemon]\n", Files.readString(file))
        assertThrows(FileAlreadyExistsException::class.java) { SecureFile.createNew0600(file, "x".toByteArray()) }
        assertEquals("[daemon]\n", Files.readString(file), "the file already there is untouched")
    }

    @Test
    fun `createNew0600 on a filesystem without POSIX modes still creates the file`(@TempDir tmp: Path) {
        val zip = URI.create("jar:${tmp.resolve("config.zip").toUri()}")
        FileSystems.newFileSystem(zip, mapOf("create" to "true")).use { fs ->
            val file = fs.getPath("/splice.toml")

            SecureFile.createNew0600(file, "[daemon]\n".toByteArray())

            assertEquals("[daemon]\n", Files.readString(file))
        }
    }

    // V4-278: the start's hold on splice.toml and its backups. Group and other bits go, the owner's stay.
    @Test
    fun `ownerOnlyFile drops what others could read, keeps the owner's bits, and says what it did`(@TempDir tmp: Path) {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"), "POSIX modes")
        val open = Files.writeString(tmp.resolve("open.toml"), "x")
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rw-rw-r--"))
        val readOnly = Files.writeString(tmp.resolve("read-only.toml"), "x")
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r--------"))

        assertEquals(FileTightening.Tightened("rw-rw-r--", "rw-------"), SecureFile.ownerOnlyFile(open))
        assertEquals(FileTightening.Held, SecureFile.ownerOnlyFile(open), "a second hold has nothing to do")
        assertEquals(FileTightening.Held, SecureFile.ownerOnlyFile(readOnly))
        assertEquals("r--------", PosixFilePermissions.toString(Files.getPosixFilePermissions(readOnly)))
    }

    @Test
    fun `ownerOnlyFile answers why a file stayed open, never throws`(@TempDir tmp: Path) {
        val missing = SecureFile.ownerOnlyFile(tmp.resolve("gone.toml"))
        assertTrue(missing is FileTightening.Open, "a file that is not there: $missing")
        val zip = URI.create("jar:${tmp.resolve("config.zip").toUri()}")
        FileSystems.newFileSystem(zip, mapOf("create" to "true")).use { fs ->
            val file = Files.writeString(fs.getPath("/splice.toml"), "x")

            assertEquals(FileTightening.Open("its filesystem keeps no POSIX modes"), SecureFile.ownerOnlyFile(file))
        }
    }
}
