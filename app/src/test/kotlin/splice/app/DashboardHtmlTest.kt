// NEW: the shipped dashboard is the default; an explicit override never silently falls back.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DashboardHtmlTest {
    @Test
    fun `unset override serves the packaged console`() {
        assertEquals("<html>packaged</html>", page(null))
    }

    @Test
    fun `absolute explicit override is served and reread`(@TempDir tmp: Path) {
        val file = tmp.resolve("index.html")
        Files.writeString(file, "<html>explicit</html>")
        val source = DashboardHtml(EnvReader { file.toString() }).source {
            error("an explicit override must not read the packaged console")
        }
        assertEquals("<html>explicit</html>", source())
        Files.writeString(file, "<html>changed</html>")
        assertEquals("<html>changed</html>", source())
    }

    @Test
    fun `missing explicit override reports unreadable instead of serving the jar`(@TempDir tmp: Path) {
        assertUnreadable(tmp.resolve("missing/index.html").toString())
    }

    @Test
    fun `relative explicit override reports unreadable instead of serving the jar`() {
        assertUnreadable("console/dist/index.html")
    }

    @Test
    fun `blank explicit override reports unreadable instead of serving the jar`() {
        assertUnreadable("")
    }

    @Test
    fun `directory explicit override reports unreadable instead of serving the jar`(@TempDir tmp: Path) {
        assertUnreadable(tmp.toString())
    }

    @Test
    fun `invalid explicit override reports unreadable without quoting it as HTML`() {
        assertUnreadable("bad\u0000<synthetic-path>")
    }

    private fun assertUnreadable(path: String) {
        val html = page(path)
        assertTrue(
            html.contains("SPLICE_CONSOLE_HTML override path is unreadable"),
            "override failure needs a visible diagnostic",
        )
        assertFalse(html.contains("<html>packaged</html>"), "an explicit failure must not fall back")
        assertFalse(html.contains("<synthetic-path>"), "a configured path must not become markup")
    }

    private fun page(path: String?): String =
        DashboardHtml(
            EnvReader { name ->
                assertEquals("SPLICE_CONSOLE_HTML", name)
                path
            },
        ).source { "<html>packaged</html>" }()
}
