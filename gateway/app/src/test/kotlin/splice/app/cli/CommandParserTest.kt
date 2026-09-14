// `splice login <head> [--label <name>]` (v0.4.0, FEATURES.md §11): the label rides the parsed
// command as data, wherever the flag sits after the verb, and its absence is null, not "".
package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandParserTest {

    private val parser = CommandParser()

    @Test
    fun `login carries the head and the optional label`() {
        assertEquals(Command.Login("claudex", null), parser.parse(arrayOf("login", "claudex")))
        assertEquals(Command.Login("claudex", "work"), parser.parse(arrayOf("login", "claudex", "--label", "work")))
        assertEquals(Command.Login("claudex", "work"), parser.parse(arrayOf("login", "--label", "work", "claudex")))
        assertEquals(Command.Login(null, "work"), parser.parse(arrayOf("login", "--label", "work")))
        assertEquals(null, parser.parse(arrayOf("login", "claudex", "--label")), "a bare --label is refused")
    }
}
