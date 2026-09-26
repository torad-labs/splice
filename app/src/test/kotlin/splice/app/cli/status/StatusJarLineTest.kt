// DR-86's status arm, split from DoctorShimAbsenceTest when doctor moved to features/diagnostics
// (LAYOUT-01): `splice status`'s jar line is the same reporter contract as doctor's jar row, and
// it stayed in app with the status verb.
package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class StatusJarLineTest {

    private fun <T> withDenied(dir: Path, block: () -> T): T = try {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("---------"))
        block()
    } finally {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    }

    // DR-86 redo (codex gap): the STATUS twin. jarLine is the same reporter contract, and its
    // unreadable branch was unpinned — reverting it to the old selfJar()?.toString() left every
    // permanent suite green. Same fixture as the doctor arm: installed jar under a tmp home,
    // parent denied, the line must say unreadable rather than render the healthy path.
    @Test
    fun `status jarLine names an unreadable jar instead of the healthy path - DR-86`(@TempDir tmp: Path) {
        val savedHome = System.getProperty("user.home")
        val spliceShare = Files.createDirectories(
            tmp.resolve("home").resolve(".local").resolve("share").resolve("splice"),
        )
        Files.writeString(spliceShare.resolve("splice.jar"), "jar-bytes")
        System.setProperty("user.home", tmp.resolve("home").toString())
        try {
            val healthy = StatusCommand().jarLine()
            assertTrue(healthy.endsWith("splice.jar"), healthy) // control: a readable jar is a path
            val denied = withDenied(spliceShare) { StatusCommand().jarLine() }
            assertTrue(denied.contains("unreadable"), denied)
        } finally {
            System.setProperty("user.home", savedHome)
        }
    }
}
