// `splice status` names an unreadable installed jar instead of rendering the healthy path.
package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
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

    @Test
    fun `status jarLine names an unreadable jar instead of the healthy path`(@TempDir tmp: Path) {
        val spliceShare = Files.createDirectories(
            tmp.resolve("home").resolve(".local").resolve("share").resolve("splice"),
        )
        Files.writeString(spliceShare.resolve("splice.jar"), "jar-bytes")
        UserHome.within(tmp.resolve("home")) {
            val healthy = StatusCommand().jarLine()
            assertTrue(healthy.endsWith("splice.jar"), healthy) // control: a readable jar is a path
            val denied = withDenied(spliceShare) { StatusCommand().jarLine() }
            assertTrue(denied.contains("unreadable"), denied)
        }
    }
}
