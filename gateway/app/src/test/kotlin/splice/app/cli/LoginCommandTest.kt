package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import java.nio.file.Paths

class LoginCommandTest {

    @Test
    fun `oauth login writes to the provider configured auth file`() {
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_RESPONSES,
            baseUrl = "https://example.invalid",
            auth = AuthConfig("chatgpt-oauth", file = "/tmp/splice-custom-auth.json"),
        )
        assertEquals(
            Paths.get("/tmp/splice-custom-auth.json"),
            LoginCommand().oauthAuthPath(provider, "~/.codex/auth.json"),
        )
    }

    // DR-97: the login call site must hand the HEAD key to the masked prompt — the daemon reads
    // effectiveApiKeyEnv(ctx.key) in every arm, and a provider-key derivation stored the key
    // under a var nothing reads (login success, head 401s, doctor "not set"). No interactive
    // console in a test JVM, so the piped-fallback line carries the derived var to stdout.
    @Test
    fun `api-key login derives the env var from the HEAD key - DR-97`() {
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "https://example.invalid",
            auth = AuthConfig("api-key"),
        )
        val out = java.io.ByteArrayOutputStream()
        val saved = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            kotlinx.coroutines.runBlocking {
                LoginCommand().runLoginFlow("fast", provider, Topology())
            }
        } finally {
            System.setOut(saved)
        }
        assertTrue(out.toString().contains("FAST_API_KEY"), "prompt must name the var the daemon reads:\n$out")
    }
}
