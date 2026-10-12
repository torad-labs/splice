package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.daemonclient.DaemonProbe
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

    // A kimi-oauth head with default config read permanently not-signed-in — the registry
    // row carried null (claiming a "provider-computed path" nothing computes) while every working
    // path hard-fell-back to the same literal, so credentialConfigured resolved NO file: status
    // showed login-needed and doctor FAILed forever against a serving head. Since 2026-09-05 the
    // default is splice's own ~/.config/splice/auth/kimi.json (AuthKind header), never the app's.
    @Test
    fun `kimi-oauth default credential file counts as configured`(@TempDir tmp: Path) {
        val provider = ProviderConfig(
            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
            baseUrl = "https://example.invalid",
            auth = AuthConfig("kimi-oauth"),
        )
        val creds = Files.createDirectories(tmp.resolve(".config").resolve("splice").resolve("auth"))
        Files.writeString(creds.resolve("kimi.json"), """{"access_token":"k"}""")
        UserHome.within(tmp) {
            assertTrue(StatusCommand().authPresent("kimi", provider) { null })
        }
    }

    // The warning crosses the wire as the daemon's /health body says it: parsed the way the CLI parses it, then drawn
    // by the real status verb under the wordmark.
    @Test
    fun `status prints the newer-client warning a health body carries, and none when it carries none`(
        @TempDir home: Path,
    ) {
        val warning = "Claude Code 9.9.9 is newer than the version splice 0.4.0 was tested with (2.1.289)"
        val warned = """{"version":"0.0.1","ok":true,"clientVersionWarning":"$warning"}"""
        val quiet = """{"version":"0.0.1","ok":true}"""

        UserHome.within(home) {
            val withWarning = printed { StatusCommand(healthProbe = { DaemonProbe.parseHealth(warned) }).status() }
            val without = printed { StatusCommand(healthProbe = { DaemonProbe.parseHealth(quiet) }).status() }

            assertTrue(withWarning.lines().any { it.contains("! $warning") }, withWarning)
            assertFalse(without.contains("newer than"), without)
        }
    }

    private fun printed(block: () -> Unit): String {
        val buffer = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
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
