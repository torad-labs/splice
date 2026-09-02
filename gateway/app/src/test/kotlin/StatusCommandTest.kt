import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.LoginKimi
import splice.app.cli.StatusCommand
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import java.nio.file.Files
import java.nio.file.Path

class StatusCommandTest {

    @Test
    fun `api-key auth requires a nonblank environment value`() {
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "https://example.invalid",
            auth = AuthConfig("api-key", env = "VENDOR_KEY"),
        )
        assertFalse(StatusCommand().authPresent("vendor", provider) { "" })
        assertFalse(StatusCommand().authPresent("vendor", provider) { null })
        assertTrue(StatusCommand().authPresent("vendor", provider) { "secret" })
    }

    @Test
    fun `api-key auth with no explicit env reads the derived KEY_API_KEY default`() {
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "https://example.invalid",
            auth = AuthConfig("api-key"),
        )
        // No auth.env — the head reads the same derived <KEY>_API_KEY the daemon wires, so the CLI
        // must not report it "not signed in" while the daemon serves it fine.
        assertTrue(
            StatusCommand().authPresent("openrouter", provider) { name ->
                "k".takeIf { name == "OPENROUTER_API_KEY" }
            },
        )
        assertFalse(
            StatusCommand().authPresent("openrouter", provider) { name -> "k".takeIf { name == "UNRELATED" } },
        )
    }

    // DR-98: a kimi-oauth head with default config read permanently not-signed-in — the registry
    // row carried null (claiming a "provider-computed path" nothing computes) while every working
    // path hard-falls-back to ~/.kimi/credentials/kimi-code.json, so credentialConfigured resolved
    // NO file: status showed login-needed and doctor FAILed forever against a serving head.
    @Test
    fun `kimi-oauth default credential file counts as configured - DR-98`(@TempDir tmp: Path) {
        val provider = ProviderConfig(
            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
            baseUrl = "https://example.invalid",
            auth = AuthConfig("kimi-oauth"),
        )
        val creds = Files.createDirectories(tmp.resolve(".kimi").resolve("credentials"))
        Files.writeString(creds.resolve("kimi-code.json"), """{"access_token":"k"}""")
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.toString())
        try {
            assertTrue(StatusCommand().authPresent("kimi", provider) { null })
        } finally {
            System.setProperty("user.home", savedHome)
        }
    }

    @Test
    fun `wrapper status honors the configured bin directory`(@TempDir root: Path) {
        val bin = root.resolve("custom-bin")
        Files.createDirectories(bin)
        Files.createSymbolicLink(bin.resolve("claudex"), root.resolve("splice-launch"))
        val env: (String) -> String? = { name -> if (name == "SPLICE_BIN_DIR") bin.toString() else null }
        assertTrue(StatusCommand().wrapperInstalled("claudex", env))
    }
}

// DR-175 (grok-splice source sweep): the status table's backend column told kimi operators they
// were on "OpenAI platform". backendLabel matched three wire STRINGS and let everything else fall
// through to a dialect guess whose else-branch was that literal, and kimi ships as
// anthropic-passthrough + kimi-oauth, so it landed there. AuthKind.kt's own comment says
// knownKinds() exists so matrices "derive their denominator from the registry rather than
// maintaining a second list that can silently omit a new kind" — and that `when` was the second
// list. These arms take the denominator from the registry for exactly that reason.
class BackendLabelTest {

    private fun provider(kind: String, dialect: Dialect) = ProviderConfig(
        dialect = dialect,
        baseUrl = "https://example.invalid",
        auth = AuthConfig(kind),
    )

    @Test
    fun `the shipped kimi pair never renders an OpenAI label - DR-175`() {
        val label = LoginKimi().backendLabel(provider("kimi-oauth", Dialect.ANTHROPIC_PASSTHROUGH))
        assertFalse(label.contains("OpenAI"), "kimi is Moonshot, not OpenAI: $label")
        assertTrue(label.contains("Moonshot"), label)
    }

    @Test
    fun `the documented kimi api-key alternative is not OpenAI either - DR-175`() {
        // config/splice.example.toml documents MOONSHOT_API_KEY over anthropic-passthrough as the
        // pay-per-token path. It is an UNREGISTERED kind, so it takes the dialect fallback — which
        // must still describe the wire rather than naming a vendor it cannot verify.
        val label = LoginKimi().backendLabel(provider("api-key", Dialect.ANTHROPIC_PASSTHROUGH))
        assertFalse(label.contains("OpenAI"), "an Anthropic-wire head is not an OpenAI one: $label")
    }

    @Test
    fun `no REGISTERED auth kind falls through to the dialect guess - DR-175`() {
        // The denominator is the registry, not a list retyped here: a kind added to AuthKind
        // without a label reds this arm (and fails to compile, which is the real wall).
        val fallbacks = setOf("OpenAI-compatible", "OpenAI platform", "Anthropic-compatible")
        AuthKindRegistry.knownKinds().forEach { kind ->
            Dialect.entries.forEach { dialect ->
                val label = LoginKimi().backendLabel(provider(kind.wire, dialect))
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
        val kimi = LoginKimi()
        assertEquals("codex / ChatGPT", kimi.backendLabel(provider("chatgpt-oauth", Dialect.OPENAI_RESPONSES)))
        assertEquals("xAI Grok", kimi.backendLabel(provider("grok-oauth", Dialect.OPENAI_RESPONSES)))
        assertEquals("Anthropic (your login)", kimi.backendLabel(provider("client", Dialect.ANTHROPIC_PASSTHROUGH)))
        assertEquals("OpenAI-compatible", kimi.backendLabel(provider("api-key", Dialect.OPENAI_CHAT)))
    }
}
