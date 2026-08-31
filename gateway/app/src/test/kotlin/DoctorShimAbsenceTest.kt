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

    // DR-85 (batches 6+7 review): a DANGLING shim is neither missing nor an access problem — it
    // needs exactly the reinstall the unreadable branch forbids. MgmtKey/DoctorPathCheck already
    // carry the three-way idiom; these pin it onto the shim surfaces.
    @Test
    fun `doctor's shim check names a dangling shim and the reinstall remedy - DR-85`(@TempDir tmp: Path) {
        val share = Files.createDirectories(tmp.resolve("share"))
        Files.createSymbolicLink(share.resolve("splice-launch"), share.resolve("gone-target"))
        Files.createDirectories(tmp.resolve("bin"))
        val checks = DoctorInstallProbes(DoctorProbes()).installationChecks(DoctorTopology.Absent, shareEnv(tmp))
        val shim = checks.single { it.toString().contains("launch shim") }
        assertTrue(shim.toString().contains("dangling"), shim.toString())
        assertTrue(shim.toString().contains("install.sh"), shim.toString())
    }

    @Test
    fun `a dangling shim staleness warning names dangling, not access - DR-85`(@TempDir tmp: Path) {
        val share = Files.createDirectories(tmp.resolve("share"))
        Files.createSymbolicLink(share.resolve("splice-launch"), share.resolve("gone-target"))
        val warning = InstallShim().shimStalenessWarning(shareEnv(tmp))
        assertTrue(warning != null && warning.contains("dangling"), warning ?: "<null>")
    }

    // DR-86 (batches 6+7 review): jarCheck is a REPORTER — DR-70's "return the path and let the
    // consumer fail" is right for the spawn consumer, but doctor rendering OK for a jar it cannot
    // stat inverts DR-69's own contract. The row must name the third state.
    @Test
    fun `doctor's jar check names an unreadable jar instead of OK - DR-86`(@TempDir tmp: Path) {
        val savedHome = System.getProperty("user.home")
        val spliceShare = Files.createDirectories(
            tmp.resolve("home").resolve(".local").resolve("share").resolve("splice"),
        )
        Files.writeString(spliceShare.resolve("splice.jar"), "jar-bytes")
        Files.createDirectories(tmp.resolve("share"))
        Files.createDirectories(tmp.resolve("bin"))
        System.setProperty("user.home", tmp.resolve("home").toString())
        val checks = try {
            withDenied(spliceShare) {
                DoctorInstallProbes(DoctorProbes()).installationChecks(DoctorTopology.Absent, shareEnv(tmp))
            }
        } finally {
            System.setProperty("user.home", savedHome)
        }
        val jar = checks.single { it.toString().contains("name=jar") }
        assertTrue(jar.toString().contains("unreadable"), jar.toString())
    }
}
