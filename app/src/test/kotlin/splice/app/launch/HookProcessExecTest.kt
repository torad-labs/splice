// NEW: pins the :app half of the exec-probe port (V4-103) — a clean script execs to null and a
// failing script names its exit code, so the noexec contract is proven against the real
// ProcessBuilder rather than the fake :core uses in its file-I/O tests.
package splice.app.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class HookProcessExecTest {

    @Test
    fun `a clean exit reports null - the ordinary-dir false-positive guard`(@TempDir tmp: Path) {
        val script = tmp.resolve("ok.sh")
        Files.writeString(script, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))

        assertNull(HookProcessExec.exec(script, 15), "an exec-able owner-only script must not read as noexec")
    }

    @Test
    fun `a non-zero exit is an IOException naming the exit code`(@TempDir tmp: Path) {
        val script = tmp.resolve("bad.sh")
        Files.writeString(script, "#!/bin/sh\nexit 7\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))

        val failure = HookProcessExec.exec(script, 15)
        assertTrue(failure is IOException, "got $failure")
        assertEquals("exec probe exited 7", failure?.message)
    }
}
