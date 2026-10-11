package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

// `splice console` hands the page its key through a redirect page only its owner can read, in the address fragment,
// which a browser never sends to the daemon.
class ConsoleLaunchPageTest {
    @Test
    fun `the launch page is owner-only and carries the key in the fragment alone`(@TempDir dir: Path) {
        val page = ConsoleCommand(browser = { true }).launchPage(dir, "http://127.0.0.1:3096", "k+y/1")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(page)))
        val html = Files.readString(page)
        assertTrue(html.contains("url=http://127.0.0.1:3096/#k=k%2By%2F1\""), html)
        assertEquals(1, Regex("k%2By%2F1").findAll(html.substringBefore("<a")).count(), "the key appears once, after #")
    }
}
