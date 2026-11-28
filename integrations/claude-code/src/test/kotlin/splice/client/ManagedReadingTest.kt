// ManagedSettings.reading under execute-only (`--x--x--x`) platform folders: a lookup by name needs no listing, but
// the folder's other contents (drop-ins included) were not seen, so the unlistable folder itself is named explicitly.
package splice.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ManagedReadingTest(@param:TempDir private val tmp: Path) {
    private val folder = tmp.resolve("claude-code")
    private val dropIns = folder.resolve("managed-settings.d")
    private val env = EnvReader { if (it == "HOME") tmp.resolve("home").toString() else null }

    @Test
    fun `an empty listable folder establishes absence for the main file and drop-ins`() {
        Files.createDirectories(folder)
        val reading = ManagedSettings("Linux", platformDir = folder, policy = PlatformPolicy("Linux")).reading(env)
        assertEquals(ManagedFile.Absent, reading.state)
        assertTrue(reading.read.isEmpty())
        assertFalse(dropIns.toString() in reading.unread, reading.unread.toString())
    }

    @Test
    fun `an execute-only folder reports Unlistable and names that folder in unread`() {
        Files.createDirectories(folder)
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("--x--x--x"))
        try {
            assumeFalse(Files.isReadable(folder), "a user that can read anything cannot show this")
            val reading = ManagedSettings("Linux", platformDir = folder, policy = PlatformPolicy("Linux")).reading(env)
            assertEquals(ManagedFile.Unlistable(folder), reading.state)
            assertTrue(reading.read.isEmpty())
            assertTrue("$folder (folder could not be listed)" in reading.unread, reading.unread.toString())
            assertFalse(dropIns.toString() in reading.unread, reading.unread.toString())
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `a readable main file inside an execute-only folder is read while the unlistable folder stays unread`() {
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("managed-settings.json"), """{"parentSettingsBehavior":"merge"}""")
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("--x--x--x"))
        try {
            assumeFalse(Files.isReadable(folder), "a user that can read anything cannot show this")
            val reading = ManagedSettings("Linux", platformDir = folder, policy = PlatformPolicy("Linux")).reading(env)
            assertTrue(reading.state is ManagedFile.Read, reading.state.toString())
            assertEquals(1, reading.read.size)
            assertTrue("$folder (folder could not be listed)" in reading.unread, reading.unread.toString())
            assertFalse(dropIns.toString() in reading.unread, reading.unread.toString())
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
}
