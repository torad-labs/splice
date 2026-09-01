package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import java.nio.file.Files
import java.nio.file.Path
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

    // DR-97: the masked prompt must derive its var from the HEAD key — the daemon reads
    // effectiveApiKeyEnv(ctx.key) in every arm, and a provider-key derivation stored the key
    // under a var nothing reads (login success, head 401s, doctor "not set"). No interactive
    // console in a test JVM, so the piped-fallback line carries the derived var to stdout.
    // This arm pins runLoginFlow's OWN contract; the call site that feeds it is pinned by the
    // login() arm below (review 2026-08-31: this one alone cannot see login() pass the provider
    // key, and the two were conflated in this comment).
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

    /** DR-97 (coverage redo, review 2026-08-31): the arm above drives runLoginFlow with a literal
     *  head key, so it stays GREEN when login() itself regresses to passing the provider key —
     *  the actual defect. This one enters at login(), the production entry Command.Login calls,
     *  and reads the derived var off the real resolution chain: config -> head -> provider ->
     *  masked prompt. `[heads.fast] provider = "openrouter"` with NO explicit auth.env is the
     *  shape that discriminates: head-derived is FAST_API_KEY, provider-derived OPENROUTER_API_KEY.
     *  user.home is redirected so BOTH the config path and the login receipt land in the temp
     *  tree (StatePaths reads the same property) — the DR-111 law: a test never writes a real
     *  receipt. */
    @Test
    fun `login derives the api-key env var from the HEAD key at the real call site - DR-97`(@TempDir tmp: Path) {
        val config = tmp.resolve(".config").resolve("splice").resolve("splice.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, FAST_HEAD_TOML)
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.toString())
        val out = java.io.ByteArrayOutputStream()
        val savedOut = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            // PREMISE, asserted not assumed: an ambient SPLICE_CONFIG / XDG_CONFIG_HOME would
            // point login() at the operator's own config and make every assertion below vacuous.
            assertEquals(
                config,
                TopologyLoader.configPath(),
                "the redirected config path must be the one login() reads",
            )
            kotlinx.coroutines.runBlocking { LoginCommand().login("fast") }
        } finally {
            System.setOut(savedOut)
            System.setProperty("user.home", savedHome)
        }
        val printed = out.toString()
        // PREMISE: the no-console fallback is what carries the var to stdout (JDK 21 returns a
        // null Console off a tty). If a JDK ever hands tests a Console this fails loudly here
        // rather than passing without having read the var at all.
        assertTrue(printed.contains("splice key set"), "the no-console fallback must have run:\n$printed")
        assertTrue(printed.contains("FAST_API_KEY"), "login() must derive the var from the HEAD key:\n$printed")
        assertFalse(
            printed.contains("OPENROUTER_API_KEY"),
            "the PROVIDER key must never name the var — that is the DR-97 defect:\n$printed",
        )
    }
}

/** A head whose key differs from its provider's, with no explicit auth.env — the only shape in
 *  which head-derived and provider-derived env vars differ (AuthConfig.effectiveApiKeyEnv). */
private const val FAST_HEAD_TOML = """
[daemon]
control_port = 3096

[providers.openrouter]
dialect = "openai-chat"
base_url = "https://example.invalid"
auth = { kind = "api-key" }

[[providers.openrouter.models]]
id = "m"
label = "M"
context_window = 200000

[heads.fast]
provider = "openrouter"
port = 3101
discovery_prefix = "claude-fast--"
pinned_model = "m"

[heads.fast.claude]
command = "claude-fast"
"""
