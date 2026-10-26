// `splice console` opens the page it wrote through the desktop's browser opener, while a link from a provider reaches
// that opener only as a web link. From Oct 19 to Oct 26 the page was refused along with the provider links, since its
// file: address is no web link, so `splice console` opened nothing and printed the page's path. A test never launches
// the desktop's opener, so this reads the command each path would hand it.
package splice.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import java.nio.file.Files
import java.nio.file.Path

class SystemBrowserOpenerTest {

    private val opener = SystemBrowserOpener(TerminalOutput { })

    @Test
    fun `the page splice console wrote reaches the desktop's opener by its own file address`(@TempDir dir: Path) {
        val page = dir.resolve("console-open.html")
        Files.writeString(page, "<!doctype html><title>splice</title>")
        val command = opener.pageCommand(page)
        assertTrue(command?.first() in DESKTOP_OPENERS, "the desktop's opener runs: $command")
        assertEquals(page.toUri().toString(), command?.last(), "and is handed the page's own address")
    }

    @Test
    fun `a provider's link reaches the desktop's opener only as an http or https link that names a host`() {
        val link = "https://auth.example.com/device?user_code=WDJB-MJHT"
        assertEquals(link, opener.linkCommand(link)?.last())
        val refused =
            listOf("file:///etc/passwd", "smb://host/share", "custom-handler:run", "https:///no-host", "not a link")
        for (other in refused) {
            assertNull(opener.linkCommand(other), other)
        }
    }
}

/** The programs a Mac and a Linux desktop open a link or a page with. */
private val DESKTOP_OPENERS = setOf("open", "xdg-open")
