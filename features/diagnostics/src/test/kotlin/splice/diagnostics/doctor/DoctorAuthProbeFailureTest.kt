// NEW: V4-112 — one of the two BEHAVIOUR changes the widened-wall burn-down in :app made, pinned so
// it cannot silently revert (the `splice add` half lives in cli/add/AddModelsNullIdTest.kt).
//   kt-no-silent-result-collapse: doctor's `gh auth status` probe collapsed a spawn failure into
//   `false`, i.e. into "installed but not authenticated — gh auth login". A probe that could not
//   RUN is a different fact, and prescribing a login for an exec failure is the 2026-07-18
//   misdiagnosis shape this wall exists for.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DoctorAuthProbeFailureTest {
    @Test
    fun `a gh whose auth probe cannot even run is not reported as unauthenticated`(@TempDir tmp: Path) {
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val gh = bin.resolve("gh")
        // A shebang naming an interpreter that does not exist: binaryOnPath still FINDS it (the x
        // bit is set), and execve fails with ENOENT before any interpreter runs — a spawn failure
        // (start() throws), not a non-zero exit from a real gh. An execute-only script does NOT
        // produce this shape on Linux: the kernel still reads the shebang, /bin/sh starts, then
        // exits non-zero when it cannot read the body — which the code correctly renders as
        // "not authenticated", so that shape cannot be the pin for this branch.
        Files.writeString(gh, "#!/nonexistent/interpreter/splice-test\n")
        Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwx------"))
        val pathEnv = EnvReader { name -> if (name == "PATH") bin.toString() else null }
        val check = DoctorInstallProbes(DoctorProbes(DoctorTestPorts.noJar), DoctorTestPorts.noJar).ghCheck(pathEnv)
        assertEquals(CheckStatus.WARN, check.status)
        assertTrue(check.detail.contains("could not be run"), check.detail)
        assertFalse(check.detail.contains("not authenticated"), check.detail)
    }
}
