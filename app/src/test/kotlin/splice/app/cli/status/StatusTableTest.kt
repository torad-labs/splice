// The status table: a row names the one command that fixes it, state survives NO_COLOR, columns hold their grid,
// and the backend column never names a vendor the head is not on (kimi is not "OpenAI platform").
package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class StatusTableTest {

    private val table = StatusTable()

    private fun provider(kind: String, dialect: Dialect) = ProviderConfig(
        dialect = dialect,
        baseUrl = "https://example.invalid",
        auth = AuthConfig(kind),
    )

    // ROW HAD NO ARMS AT ALL before 2026-09-22. Every assertion in this class called backendLabel,
    // so the table's actual layout — glyph, columns, the action text — could be rewritten wholesale
    // and the suite stayed green. It was, and it did. These arms exist so the next rewrite cannot.

    private fun head(command: String, port: Int = 3099) =
        HeadConfig(
            provider = "p",
            port = port,
            discoveryPrefix = "test--",
            pinnedModel = "test-model",
            claude = ClaudeWrapperConfig(command = command),
        )

    /** Hermetic: SPLICE_BIN_DIR decides wrapperInstalled, and an explicit auth.env decides the
     *  api-key credential, so neither answer comes from the developer's own machine. */
    private fun env(bin: Path, vararg extra: Pair<String, String>): EnvReader {
        val map = mapOf("SPLICE_BIN_DIR" to bin.toString()) + extra.toMap()
        return EnvReader { name -> map[name] }
    }

    private fun apiKeyProvider() = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://example.invalid",
        auth = AuthConfig(kind = "api-key", env = "TEST_STATUS_KEY"),
    )

    /** A topology of the given heads, all routed to [apiKeyProvider] under key "p". */
    private fun topology(vararg heads: Pair<String, HeadConfig>) =
        Topology(providers = mapOf("p" to apiKeyProvider()), heads = linkedMapOf(*heads))

    /** The single data row of a one-head table (line 0 is the header). */
    private fun onlyRow(table: StatusTable, command: String, env: EnvReader): String =
        table.lines(topology("or" to head(command)), env)[1]

    private fun ready(bin: Path, command: String): EnvReader {
        Files.createSymbolicLink(bin.resolve(command), bin.resolve("target"))
        return env(bin, "TEST_STATUS_KEY" to "sk-present")
    }

    @Test
    fun `a blocked row names the one command that would fix it`(@TempDir bin: Path) {
        val plain = StatusTable(CliPalette(ColorDepth.NONE))
        val row = onlyRow(plain, "claude-or", env(bin))
        // No wrapper and no key. The wrapper is the blocking one, so that is what it must say —
        // telling the operator to set a key for a command that is not on PATH is advice they
        // cannot act on yet.
        assertTrue(row.contains("splice install"), row)
        assertFalse(row.contains("login"), "two actions in one row is no action: $row")
    }

    @Test
    fun `a keyless api-key row names the login command, not an instruction`(@TempDir bin: Path) {
        // The column's contract is a COMMAND the operator types. "set the api key" is an errand:
        // it names no variable, no file and no verb. `<command> login` is the real fix for an
        // api-key head too — the launch shim routes it to LoginCommand, which prompts for the key
        // and stores it where the daemon reads it.
        val plain = StatusTable(CliPalette(ColorDepth.NONE))
        Files.createSymbolicLink(bin.resolve("claude-or"), bin.resolve("target"))
        val row = onlyRow(plain, "claude-or", env(bin))
        assertTrue(row.contains("claude-or login"), row)
    }

    @Test
    fun `state survives NO_COLOR, carried by the glyph rather than the tone`(@TempDir bin: Path) {
        val plain = StatusTable(CliPalette(ColorDepth.NONE))
        val blocked = onlyRow(plain, "claude-or", env(bin))
        val live = onlyRow(plain, "claude-or", ready(bin, "claude-or"))
        assertFalse(blocked.contains("\u001B"), "an SGR sequence reached a NO_COLOR terminal: $blocked")
        assertFalse(live.contains("\u001B"), live)
        assertNotEquals(
            blocked.trimStart().first(),
            live.trimStart().first(),
            "the two states open with the same glyph, so colour was the only carrier",
        )
        assertTrue(live.contains("ready"), live)
    }

    @Test
    fun `the action column holds its offset when head names differ in length`(@TempDir bin: Path) {
        val plain = StatusTable(CliPalette(ColorDepth.NONE))
        // Longer than any value in the shipped example — the widths used to be constants sized to
        // that example, and an operator's real topology (claude-bonsai-second) broke every column.
        val lines = plain.lines(topology("or" to head("or"), "bonsai-second" to head("claude-bonsai-second")), env(bin))
        val (header, short, long) = lines
        assertEquals(
            short.indexOf("splice install"),
            long.indexOf("splice install"),
            "columns drifted, so the table stops scanning as a grid:\n${lines.joinToString("\n")}",
        )
        // ...and the labels sit over their data, not one gutter to the left of it.
        assertEquals(header.indexOf("port"), short.indexOf("3099"), lines.joinToString("\n"))
    }

    @Test
    fun `a topology with no heads says how to connect a plan without a failed row`() {
        val lines = StatusTable(CliPalette(ColorDepth.NONE)).lines(Topology(), EnvReader { null })
        assertEquals(2, lines.size, lines.toString())
        assertTrue(lines.first().contains("upstream"), lines.toString())
        assertTrue(lines.last().contains("not set up yet"), lines.toString())
        assertTrue(lines.last().contains("splice setup"), lines.toString())
        assertFalse(lines.last().contains("failed", ignoreCase = true), lines.toString())
    }

    @Test
    fun `the shipped kimi pair never renders an OpenAI label`() {
        val label = table.backendLabel(provider("kimi-oauth", Dialect.ANTHROPIC_PASSTHROUGH))
        assertFalse(label.contains("OpenAI"), "kimi is Moonshot, not OpenAI: $label")
        assertTrue(label.contains("Moonshot"), label)
    }

    @Test
    fun `a local runtime is labelled local, never as a vendor or a subscription`() {
        val local = ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "http://localhost:11434/v1",
            auth = AuthConfig("api-key"),
        )
        val label = table.backendLabel(local)
        assertTrue(label.startsWith("local runtime"), label)
        assertFalse(label.contains("platform"), label)
        val hosted = table.backendLabel(provider("api-key", Dialect.OPENAI_CHAT))
        assertFalse(hosted.contains("local"), hosted)
    }

    @Test
    fun `the documented kimi api-key alternative is not OpenAI either`() {
        // app/src/main/resources/splice.example.toml documents MOONSHOT_API_KEY over anthropic-passthrough as the
        // pay-per-token path. It is an UNREGISTERED kind, so it takes the dialect fallback — which
        // must still describe the wire rather than naming a vendor it cannot verify.
        val label = table.backendLabel(provider("api-key", Dialect.ANTHROPIC_PASSTHROUGH))
        assertFalse(label.contains("OpenAI"), "an Anthropic-wire head is not an OpenAI one: $label")
    }
}
