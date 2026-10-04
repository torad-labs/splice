// NEW: V4-299 — the key-store branch of CredentialPresence. KeyStore.read answers an unreadable
// keys.toml as empty, so doctor, status, add and the console said "no credential" for a key still on
// disk, against the DR-70 rule the credential-file branch keeps: unreadable counts as configured, said.
package splice.accounts.status

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.KeyStore
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val KEY_ENV = "OPENROUTER_API_KEY"

class CredentialPresenceTest {

    private val head = ProviderConfig(
        dialect = Dialect.ANTHROPIC_PASSTHROUGH,
        baseUrl = "https://openrouter.ai/api",
        auth = AuthConfig("api-key", env = KEY_ENV),
    )

    @Test
    fun `an unreadable keys toml holding the head's key reads configured and says so - V4-299`(@TempDir tmp: Path) {
        val keys = tmp.resolve("keys.toml")
        KeyStore(keys).write(KEY_ENV, "sk-or-kept")
        val lines = mutableListOf<String>()

        Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("-wx------"))
        val configured = try {
            CredentialPresence(TerminalOutput { lines += it }).configured("openrouter", head, env(tmp))
        } finally {
            Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("rw-------"))
        }

        assertTrue(configured, "a key one chmod away is configured, never 'no credential': $lines")
        assertTrue(lines.any { "$keys unreadable" in it && "treating $KEY_ENV as configured" in it }, "$lines")
    }

    @Test
    fun `a readable keys toml without the head's key, or none at all, reads not configured quietly`(
        @TempDir tmp: Path,
    ) {
        val lines = mutableListOf<String>()
        val presence = CredentialPresence(TerminalOutput { lines += it })

        assertFalse(presence.configured("openrouter", head, env(tmp)), "no keys.toml at all")
        KeyStore(tmp.resolve("keys.toml")).write("FIREWORKS_API_KEY", "fw-other")
        assertFalse(presence.configured("openrouter", head, env(tmp)), "a store holding another head's key")
        assertTrue(lines.isEmpty(), "absence is not a failure to say: $lines")
    }

    /** keys.toml sits beside the SPLICE_CONFIG file (KeyStorePath.defaultPath); the key's env var is unset. */
    private fun env(tmp: Path) =
        EnvReader { name -> tmp.resolve("splice.toml").toString().takeIf { name == "SPLICE_CONFIG" } }
}
