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
        val twice = arrayOf("login", "claudex", "--label", "work", "--label", "other")
        assertEquals(null, parser.parse(twice), "a second --label is refused, not dropped")
        assertEquals(null, parser.parse(arrayOf("login", "claudex", "extra")), "a second word is refused")
    }

    // JW-08: `splice logs` is the answer to every remediation that used to end at "daemon.log", a
    // path in a directory doctor printed wrongly for years. LogsCommandTest drives the command
    // object directly, so it stays green even if the VERB is removed from the parse table and the
    // operator can no longer reach it. This is the seam that makes the verb exist.
    @Test
    fun `the logs verb reaches the logs command, arguments and all - JW-08`() {
        assertEquals(Command.Logs(emptyList()), parser.parse(arrayOf("logs")))
        assertEquals(Command.Logs(listOf("--tail", "50")), parser.parse(arrayOf("logs", "--tail", "50")))
        assertEquals(
            Command.Logs(listOf("--head", "codex", "--follow")),
            parser.parse(arrayOf("logs", "--head", "codex", "--follow")),
            "the verb passes its arguments through untouched — the command owns their meaning",
        )
    }
}
