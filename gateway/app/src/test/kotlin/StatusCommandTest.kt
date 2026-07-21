import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.authPresent
import splice.app.cli.wrapperInstalled
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
        assertFalse(authPresent("vendor", provider) { "" })
        assertFalse(authPresent("vendor", provider) { null })
        assertTrue(authPresent("vendor", provider) { "secret" })
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
        assertTrue(authPresent("openrouter", provider) { name -> "k".takeIf { name == "OPENROUTER_API_KEY" } })
        assertFalse(authPresent("openrouter", provider) { name -> "k".takeIf { name == "UNRELATED" } })
    }

    @Test
    fun `wrapper status honors the configured bin directory`(@TempDir root: Path) {
        val bin = root.resolve("custom-bin")
        Files.createDirectories(bin)
        Files.createSymbolicLink(bin.resolve("claudex"), root.resolve("splice-launch"))
        val env: (String) -> String? = { name -> if (name == "SPLICE_BIN_DIR") bin.toString() else null }
        assertTrue(wrapperInstalled("claudex", env))
    }
}
