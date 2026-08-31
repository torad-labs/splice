import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.StatusCommand
import splice.core.topology.AuthConfig
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
