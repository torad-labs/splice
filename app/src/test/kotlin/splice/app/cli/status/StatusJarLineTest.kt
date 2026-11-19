// `splice status` names an unreadable installed jar instead of rendering the healthy path.
package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import java.nio.file.Files
import java.nio.file.Path

class StatusJarLineTest {

    /** Runs [block] while the jar's name is a link to itself, which no one can read, root included (a folder's
     *  permissions don't stop root); the jar is put back after. */
    private fun <T> withLoopedJar(jar: Path, block: () -> T): T {
        val aside = jar.resolveSibling("${jar.fileName}.aside")
        Files.move(jar, aside)
        Files.createSymbolicLink(jar, jar.fileName)
        return try {
            block()
        } finally {
            Files.delete(jar)
            Files.move(aside, jar)
        }
    }

    @Test
    fun `status jarLine names an unreadable jar instead of the healthy path`(@TempDir tmp: Path) {
        val spliceShare = Files.createDirectories(
            tmp.resolve("home").resolve(".local").resolve("share").resolve("splice"),
        )
        val jar = Files.writeString(spliceShare.resolve("splice.jar"), "jar-bytes")
        UserHome.within(tmp.resolve("home")) {
            val healthy = StatusCommand().jarLine()
            assertTrue(healthy.endsWith("splice.jar"), healthy) // control: a readable jar is a path
            val denied = withLoopedJar(jar) { StatusCommand().jarLine() }
            assertTrue(denied.contains("unreadable"), denied)
        }
    }
}
