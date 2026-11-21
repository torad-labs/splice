// desktop-full (df-features): `splice desktop` says what of Claude Desktop works on splice in the mode Desktop is in, a
// line per feature with its state and, where it is not plainly working, why.
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.terminal.TerminalOutput

class DesktopSaidTest {
    private val features = """[
        {"name":"fast mode","mode":"standard","state":"unknown","reason":"the live test has not settled it"},
        {"name":"fast mode","mode":"app","state":"unavailable","reason":"Claude Desktop turns fast mode off"},
        {"name":"MCP","mode":"standard","state":"works","reason":null},
        {"name":"MCP","mode":"app","state":"works","reason":null}
    ]"""

    private fun said(mode: String): List<String> {
        val lines = mutableListOf<String>()
        val state = Json.parseToJsonElement("""{"mode":"$mode","door":"d","features":$features}""").jsonObject
        DesktopSaid(TerminalOutput { lines += it }).state(state)
        return lines.dropWhile { !it.startsWith("Features") }.takeWhile { !it.startsWith("Last turn") }
    }

    @Test
    fun `the features said are the mode Desktop is in, standard's while it is not in app mode`() {
        assertEquals(
            listOf(
                "Features (app mode):",
                "  fast mode: unavailable (Claude Desktop turns fast mode off)",
                "  MCP: works",
            ),
            said("app"),
        )
        assertEquals(
            listOf(
                "Features (standard mode):",
                "  fast mode: unknown (the live test has not settled it)",
                "  MCP: works",
            ),
            said("off"),
        )
    }
}
