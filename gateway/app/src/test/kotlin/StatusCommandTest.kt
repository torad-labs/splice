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
        assertFalse(authPresent(provider) { "" })
        assertFalse(authPresent(provider) { null })
        assertTrue(authPresent(provider) { "secret" })
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
