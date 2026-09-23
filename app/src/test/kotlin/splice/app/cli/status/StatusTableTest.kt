// NEW: backend-column labels live on StatusTable (extracted from LoginKimi, V4-21).
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
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

// DR-175 (grok-splice source sweep): the status table's backend column told kimi operators they
// were on "OpenAI platform". backendLabel matched three wire STRINGS and let everything else fall
// through to a dialect guess whose else-branch was that literal, and kimi ships as
// anthropic-passthrough + kimi-oauth, so it landed there. AuthKind.kt's own comment says
// knownKinds() exists so matrices "derive their denominator from the registry rather than
// maintaining a second list that can silently omit a new kind" — and that `when` was the second
// list. These arms take the denominator from the registry for exactly that reason.
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

    private fun ready(bin: Path, command: String): EnvReader {
        Files.createSymbolicLink(bin.resolve(command), bin.resolve("target"))
        return env(bin, "TEST_STATUS_KEY" to "sk-present")
    }

    @Test
    fun `a blocked row names the one command that would fix it`(@TempDir bin: Path) {
        val plain = StatusTable(CliPalette(ColorDepth.NONE))
        val row = plain.row("or", head("claude-or"), apiKeyProvider(), env(bin))
        // No wrapper and no key. The wrapper is the blocking one, so that is what it must say —
        // telling the operator to set a key for a command that is not on PATH is advice they
        // cannot act on yet.
        assertTrue(row.contains("splice install"), row)
        assertFalse(row.contains("set the api key"), "two actions in one row is no action: $row")
    }

    @Test
    fun `state survives NO_COLOR, carried by the glyph rather than the tone`(@TempDir bin: Path) {
        val plain = StatusTable(CliPalette(ColorDepth.NONE))
        val blocked = plain.row("or", head("claude-or"), apiKeyProvider(), env(bin))
        val live = plain.row("or", head("claude-or"), apiKeyProvider(), ready(bin, "claude-or"))
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
        val short = plain.row("or", head("or"), apiKeyProvider(), env(bin))
        val long = plain.row("claude-muse", head("claude-muse"), apiKeyProvider(), env(bin))
        assertEquals(
            short.indexOf("splice install"),
            long.indexOf("splice install"),
            "columns drifted, so the table stops scanning as a grid:\n$short\n$long",
        )
    }

    @Test
    fun `the shipped kimi pair never renders an OpenAI label - DR-175`() {
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
    fun `the documented kimi api-key alternative is not OpenAI either - DR-175`() {
        // app/src/main/resources/splice.example.toml documents MOONSHOT_API_KEY over anthropic-passthrough as the
        // pay-per-token path. It is an UNREGISTERED kind, so it takes the dialect fallback — which
        // must still describe the wire rather than naming a vendor it cannot verify.
        val label = table.backendLabel(provider("api-key", Dialect.ANTHROPIC_PASSTHROUGH))
        assertFalse(label.contains("OpenAI"), "an Anthropic-wire head is not an OpenAI one: $label")
    }

    @Test
    fun `no REGISTERED auth kind falls through to the dialect guess - DR-175`() {
        // The denominator is the registry, not a list retyped here: a kind added to AuthKind
        // without a label reds this arm (and fails to compile, which is the real wall).
        val fallbacks = setOf("OpenAI-compatible", "OpenAI platform", "Anthropic-compatible")
        AuthKindRegistry.knownKinds().forEach { kind ->
            Dialect.entries.forEach { dialect ->
                val label = table.backendLabel(provider(kind.wire, dialect))
                assertFalse(
                    label in fallbacks,
                    "registered kind ${kind.wire} took the unregistered dialect fallback: $label",
                )
            }
        }
    }

    @Test
    fun `the existing labels are unchanged - DR-175 control`() {
        // NEVER-BELOW-STATUS-QUO: the three labels that were already correct, and the api-key
        // OPENAI_CHAT fallback, must read exactly as they did before.
        assertEquals("codex / ChatGPT", table.backendLabel(provider("chatgpt-oauth", Dialect.OPENAI_RESPONSES)))
        assertEquals("xAI Grok", table.backendLabel(provider("grok-oauth", Dialect.OPENAI_RESPONSES)))
        assertEquals("Anthropic (your login)", table.backendLabel(provider("client", Dialect.ANTHROPIC_PASSTHROUGH)))
        assertEquals("OpenAI-compatible", table.backendLabel(provider("api-key", Dialect.OPENAI_CHAT)))
        assertEquals("Meta Muse", table.backendLabel(provider("muse-oauth", Dialect.ANTHROPIC_PASSTHROUGH)))
    }
}
