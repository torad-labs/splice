// NEW: V4-129 (FEATURES.md 4.5) — ClaudeLogins' store/select/materialize round trip. Splice never
// parses the credential bytes; every assertion here is about which bytes land where, never about
// their content shape.
package console.v4129

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.launch.ClaudeLoginResult
import splice.core.launch.ClaudeLogins
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ClaudeLoginsTest {

    private fun logins(home: Path) = ClaudeLogins(storeDir = home.resolve("store"))

    private fun configDirWithCredentials(home: Path, name: String, content: String): Path {
        val dir = home.resolve(name).createDirectories()
        dir.resolve(".credentials.json").writeText(content)
        return dir
    }

    @Test
    fun `an untouched store has no labels, no selection, and materializes nothing`(@TempDir home: Path) {
        val logins = logins(home)
        assertEquals(emptyList<String>(), logins.labels())
        assertEquals(null, logins.selected())
        val target = home.resolve("target").createDirectories()
        assertFalse(logins.materializeSelected(target))
        assertFalse(target.resolve(".credentials.json").exists())
    }

    @Test
    fun `store copies the source bytes verbatim under the label, and select then materialize lands them`(
        @TempDir home: Path,
    ) {
        val logins = logins(home)
        val work = configDirWithCredentials(home, "work-session", """{"accessToken":"work-token"}""")

        assertEquals(ClaudeLoginResult.Ok, logins.store("work", work))
        assertEquals(listOf("work"), logins.labels())
        assertEquals(null, logins.selected(), "storing does not select")

        assertEquals(ClaudeLoginResult.Ok, logins.select("work"))
        assertEquals("work", logins.selected())

        val target = home.resolve("claude-splice-tree").createDirectories()
        assertTrue(logins.materializeSelected(target))
        assertEquals("""{"accessToken":"work-token"}""", target.resolve(".credentials.json").readText())
    }

    @Test
    fun `several logins coexist, and selecting a second one changes what the next launch gets`(@TempDir home: Path) {
        val logins = logins(home)
        logins.store("work", configDirWithCredentials(home, "a", """{"t":"work"}"""))
        logins.store("personal", configDirWithCredentials(home, "b", """{"t":"personal"}"""))
        assertEquals(listOf("personal", "work"), logins.labels())

        logins.select("work")
        val target = home.resolve("target").createDirectories()
        logins.materializeSelected(target)
        assertEquals("""{"t":"work"}""", target.resolve(".credentials.json").readText())

        logins.select("personal")
        logins.materializeSelected(target)
        assertEquals(
            """{"t":"personal"}""",
            target.resolve(".credentials.json").readText(),
            "launch-time selection is live per launch, never latched to the first materialize",
        )
    }

    @Test
    fun `selecting or removing an unknown label is refused, never a silent no-op`(@TempDir home: Path) {
        val logins = logins(home)
        val selectResult = logins.select("ghost")
        assertTrue(selectResult is ClaudeLoginResult.Refused, "$selectResult")
        val removeResult = logins.remove("ghost")
        assertTrue(removeResult is ClaudeLoginResult.Refused, "$removeResult")
    }

    @Test
    fun `a malformed label is refused before anything is written`(@TempDir home: Path) {
        val logins = logins(home)
        val source = configDirWithCredentials(home, "a", """{"t":"x"}""")
        val result = logins.store("../etc/passwd", source)
        assertTrue(result is ClaudeLoginResult.Refused, "$result")
        assertEquals(emptyList<String>(), logins.labels())
    }

    @Test
    fun `storing with no credentials file present is refused`(@TempDir home: Path) {
        val logins = logins(home)
        val empty = home.resolve("empty").createDirectories()
        val result = logins.store("work", empty)
        assertTrue(result is ClaudeLoginResult.Refused, "$result")
    }

    @Test
    fun `removing the selected login clears the selection instead of leaving a stale marker`(@TempDir home: Path) {
        val logins = logins(home)
        logins.store("work", configDirWithCredentials(home, "a", """{"t":"work"}"""))
        logins.select("work")
        assertEquals("work", logins.selected())

        logins.remove("work")
        assertEquals(null, logins.selected())
        assertEquals(emptyList<String>(), logins.labels())

        val target = home.resolve("target").createDirectories()
        assertFalse(logins.materializeSelected(target), "a removed selection must not still materialize")
    }
}
