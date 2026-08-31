// DR-69 absence-class arms for doctor's install probes: an UNREADABLE wrapper/shim used to
// diagnose as "not linked"/"missing" — telling the operator to reinstall through a chmod. Only
// proven absence is missing; indeterminate access is its own FAIL naming the real remedy, and
// installedShimVersion THROWS on it instead of reading as "no marker".
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.DoctorInstallProbes
import splice.app.cli.DoctorPathCheck
import splice.app.cli.DoctorProbes
import splice.app.cli.DoctorTopology
import splice.app.cli.InstallShim
import splice.core.util.EnvReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DoctorShimAbsenceTest {

    private fun shareEnv(tmp: Path) = EnvReader { name ->
        mapOf(
            "SPLICE_SHARE_DIR" to tmp.resolve("share").toString(),
            "SPLICE_BIN_DIR" to tmp.resolve("bin").toString(),
        )[name]
    }

    private fun <T> withDenied(dir: Path, block: () -> T): T = try {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"))
        block()
    } finally {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    }

    @Test
    fun `an unreadable wrapper diagnoses as access, not as not-linked - DR-69`(@TempDir tmp: Path) {
        val bin = Files.createDirectories(tmp.resolve("bin"))
        Files.writeString(bin.resolve("claudex"), "#!/bin/sh\n")
        val check = withDenied(bin) { DoctorPathCheck(DoctorProbes()).wrapperCheck(bin.resolve("claudex"), "claudex") }
        assertTrue(check.toString().contains("unreadable"), check.toString())
        assertTrue(check.toString().contains("not missing"), check.toString())
    }

    @Test
    fun `a genuinely missing wrapper still reads not linked - DR-69 control`(@TempDir tmp: Path) {
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val check = DoctorPathCheck(DoctorProbes()).wrapperCheck(bin.resolve("claudex"), "claudex")
        assertTrue(check.toString().contains("not linked"), check.toString())
    }

    @Test
    fun `an unreadable shim throws from installedShimVersion and warns UNREADABLE - DR-69`(@TempDir tmp: Path) {
        val share = Files.createDirectories(tmp.resolve("share"))
        Files.writeString(share.resolve("splice-launch"), "SPLICE_SHIM_VERSION=\"9.9.9\"\n")
        val env = shareEnv(tmp)
        withDenied(share) {
            assertThrows(IOException::class.java) { InstallShim().installedShimVersion(env) }
            val warning = InstallShim().shimStalenessWarning(env)
            assertTrue(warning != null && warning.contains("UNREADABLE"), warning ?: "<null>")
        }
    }

    @Test
    fun `a genuinely absent shim is null marker and no warning - DR-69 control`(@TempDir tmp: Path) {
        val env = shareEnv(tmp)
        assertNull(InstallShim().installedShimVersion(env))
        assertEquals(null, InstallShim().shimStalenessWarning(env))
    }

    @Test
    fun `doctor's shim check classifies denied access as unreadable, not missing - DR-69`(@TempDir tmp: Path) {
        val share = Files.createDirectories(tmp.resolve("share"))
        Files.writeString(share.resolve("splice-launch"), "SPLICE_SHIM_VERSION=\"9.9.9\"\n")
        Files.createDirectories(tmp.resolve("bin"))
        val checks = withDenied(share) {
            DoctorInstallProbes(DoctorProbes()).installationChecks(DoctorTopology.Absent, shareEnv(tmp))
        }
        val shim = checks.single { it.toString().contains("launch shim") }
        assertTrue(shim.toString().contains("unreadable"), shim.toString())
        assertTrue(shim.toString().contains("not missing"), shim.toString())
    }
}
