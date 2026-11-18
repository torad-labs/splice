// splice's /login in the person's own Claude folder (docs/specs/door-default.md, decision 5), seen as the folder sees
// it: the one file splice lays where no /login is there, a /login the person wrote left as it is and named, and only
// splice's taken out.
package splice.client.login

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class DoorLoginTest {

    @Test
    fun `splice lays one file, its login command, in a Claude folder that has none`(@TempDir dir: Path) {
        val claude = dir.resolve(".claude")
        val login = DoorLogin(claude, dir.resolve("state"))

        assertNull(login.lay())

        val file = claude.resolve("commands").resolve("login.md")
        assertEquals(listOf(file), Files.walk(claude).use { s -> s.filter(Files::isRegularFile).toList() })
        val text = file.readText()
        assertTrue(Regex("(?m)^description: .*splice's accounts").containsMatchIn(text), text)
        // Where no hook catches it, it says what it is and how to open the page.
        assertTrue("`splice console`" in text, text)
    }

    @Test
    fun `a login the person wrote is left as it is and named, and taking splice's out leaves it`(@TempDir dir: Path) {
        val claude = dir.resolve(".claude")
        val file = Files.createDirectories(claude.resolve("commands")).resolve("login.md")
        val theirs = "---\ndescription: My own login\n---\nDo my own thing.\n"
        Files.writeString(file, theirs)
        val login = DoorLogin(claude, dir.resolve("state"))

        assertEquals(file, login.lay())
        assertEquals(theirs, file.readText())
        assertFalse(login.remove())
        assertEquals(theirs, file.readText())
    }

    @Test
    fun `a login skill of the person's is in the way too, and no command is laid beside it`(@TempDir dir: Path) {
        val claude = dir.resolve(".claude")
        val skill = Files.createDirectories(claude.resolve("skills").resolve("login")).resolve("SKILL.md")
        Files.writeString(skill, "---\ndescription: mine\n---\n")
        val login = DoorLogin(claude, dir.resolve("state"))

        assertEquals(skill, login.lay())
        assertFalse(Files.exists(claude.resolve("commands").resolve("login.md")))
    }

    @Test
    fun `splice's login is taken out, and laid again once it is gone`(@TempDir dir: Path) {
        val claude = dir.resolve(".claude")
        val login = DoorLogin(claude, dir.resolve("state"))
        val file = claude.resolve("commands").resolve("login.md")
        login.lay()

        assertTrue(login.remove())
        assertFalse(Files.exists(file))
        assertNull(login.lay())
        assertTrue(Files.exists(file))
    }
}
