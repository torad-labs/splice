package splice.app.cli.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeLogins
import splice.core.config.UserHome
import splice.core.process.LaunchOwners
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.sessions.registry.ProcessEnvironment
import splice.topology.TopologyLoader
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
            LoginCommand().oauthAuthPath(provider),
        )
    }

    // 2026-09-05: a head with no auth.file signs in to SPLICE's own file for its kind — never the
    // native app's (~/.grok/auth.json here), whose refresh rotation would invalidate splice's session.
    @Test
    fun `oauth login defaults to the splice-owned file for the kind, never the native app's`() {
        val provider = ProviderConfig(
            dialect = Dialect.OPENAI_RESPONSES,
            baseUrl = "https://example.invalid",
            auth = AuthConfig("grok-oauth"),
        )
        val path = LoginCommand().oauthAuthPath(provider)
        assertEquals(Paths.get(TopologyLoader.expandHome("~/.config/splice/auth/grok.json")), path)
        assertFalse(path.endsWith(Paths.get(".grok", "auth.json")))
    }

    @Test
    fun `muse oauth login defaults to the splice-owned file, never the Muse Code CLI's`() {
        val provider = ProviderConfig(
            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
            baseUrl = "https://api.meta.ai",
            auth = AuthConfig("muse-oauth"),
        )
        val path = LoginCommand().oauthAuthPath(provider)
        assertEquals(Paths.get(TopologyLoader.expandHome("~/.config/splice/auth/muse.json")), path)
        assertFalse(path.endsWith(Paths.get("muse", "auth.json")))
    }

    @Test
    fun `OAuth account refusal prints its authored reason`() {
        val topology = TopologyLoader.parse(OAUTH_HEAD_TOML)
        val provider = topology.providers.getValue("codex")
        val out = java.io.ByteArrayOutputStream()
        val saved = System.out
        System.setOut(java.io.PrintStream(out))
        val ok = try {
            kotlinx.coroutines.runBlocking {
                LoginCommand().runLoginFlow("codex", provider, topology, label = "Private Email")
            }
        } finally {
            System.setOut(saved)
        }

        assertFalse(ok)
        assertTrue(out.toString().contains("invalid OAuth account label"), out.toString())
        assertFalse(out.toString().contains("Private Email"), out.toString())
        assertFalse(out.toString().contains("withheld"), out.toString())
    }

    /** The masked prompt must derive its var from the HEAD key (the daemon reads it from the head key, never the
     *  provider key). Enters at login(), the production entry Command.Login calls, over the real resolution chain:
     *  config -> head -> provider -> masked prompt. `[heads.fast] provider = "openrouter"` with NO explicit auth.env
     *  discriminates: head-derived is FAST_API_KEY, provider-derived OPENROUTER_API_KEY. The home is redirected so the
     *  config path and the login receipt land in the temp tree. */
    @Test
    fun `login derives the api-key env var from the HEAD key at the real call site`(@TempDir tmp: Path) {
        val config = tmp.resolve(".config").resolve("splice").resolve("splice.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, FAST_HEAD_TOML)
        val out = java.io.ByteArrayOutputStream()
        val savedOut = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            UserHome.within(tmp) {
                // PREMISE, asserted not assumed: an ambient SPLICE_CONFIG / XDG_CONFIG_HOME would
                // point login() at the operator's own config and make every assertion below vacuous.
                assertEquals(
                    config,
                    TopologyLoader.configPath(),
                    "the redirected config path must be the one login() reads",
                )
                kotlinx.coroutines.runBlocking { LoginCommand().login("fast") }
            }
        } finally {
            System.setOut(savedOut)
        }
        val printed = out.toString()
        // PREMISE: the no-console fallback is what carries the var to stdout (JDK 21 returns a
        // null Console off a tty). If a JDK ever hands tests a Console this fails loudly here
        // rather than passing without having read the var at all.
        assertTrue(printed.contains("splice key set"), "the no-console fallback must have run:\n$printed")
        assertTrue(printed.contains("FAST_API_KEY"), "login() must derive the var from the HEAD key:\n$printed")
        assertFalse(
            printed.contains("OPENROUTER_API_KEY"),
            "the PROVIDER key must never name the var:\n$printed",
        )
    }

    // ---- `splice login <claude-head> --label`, end to end over the real session registry ----

    /** The Claude head runs over [configDir]; claudex, another head of the same provider, on 3105. */
    private fun claudeHeads(configDir: Path): Topology = TopologyLoader.parse(
        """
        [daemon]
        control_port = 3096

        [providers.anthropic]
        dialect = "anthropic-passthrough"
        base_url = "https://api.anthropic.com"
        auth = { kind = "client" }

        [[providers.anthropic.models]]
        id = "claude-opus-5-5"
        label = "Claude Opus 5.5"
        context_window = 1000000

        [heads.claude-splice]
        provider = "anthropic"
        port = 3104
        discovery_prefix = "claude-splice--"
        pinned_model = "claude-opus-5-5"

        [heads.claude-splice.claude]
        config_dir = "$configDir"

        [heads.claudex]
        provider = "anthropic"
        port = 3105
        discovery_prefix = "claudex--"
        pinned_model = "claude-opus-5-5"
        """.trimIndent(),
    )

    /** A running Claude Code session named [name], registered the way Claude Code registers one, whose
     *  launch record (the roster under tmp/state) points it at the head on [port]. The pid is this test's
     *  own, so the registry finds it alive. */
    private fun session(tmp: Path, name: String, port: Int) {
        val pid = ProcessHandle.current().pid()
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        // No updatedAt: the registry reads it STALE, alive but quiet, which still counts as running.
        Files.writeString(
            sessions.resolve("$pid.json"),
            """{"pid":$pid,"sessionId":"s-1","name":"$name","kind":"interactive"}""",
        )
        val head = mapOf(3104 to "claude-splice", 3105 to "claudex").getValue(port)
        LaunchOwners(tmp.resolve("state")).write(pid, head, "http://127.0.0.1:$port", "session", "other")
    }

    private fun claudeLabel(tmp: Path, lines: MutableList<String>) = ClaudeLoginLabel(
        output = { lines += it },
        logins = ClaudeLogins(storeDir = tmp.resolve("store")),
        sessionsDir = tmp.resolve("sessions"),
        processes = ProcessEnvironment(procRoot = tmp.resolve("proc"), owners = LaunchOwners(tmp.resolve("state"))),
    )

    private fun signedIn(tmp: Path): Path {
        val configDir = Files.createDirectories(tmp.resolve("claude-splice"))
        Files.writeString(configDir.resolve(".credentials.json"), "A-gen1")
        Files.writeString(
            configDir.resolve(".claude.json"),
            """{"oauthAccount":{"accountUuid":"uuid-a","emailAddress":"a@example.com"}}""",
        )
        return configDir
    }

    @Test
    fun `splice login on the Claude head saves its live login under the label and selects it`(
        @TempDir tmp: Path,
    ) {
        val configDir = signedIn(tmp)
        session(tmp, "a claudex session", port = 3105) // another head's session is not this head's
        val lines = mutableListOf<String>()

        val ok = claudeLabel(tmp, lines).login("claude-splice", claudeHeads(configDir), "work", discard = false)

        assertTrue(ok, "$lines")
        assertEquals("A-gen1", Files.readString(tmp.resolve("store/work.credentials.json")))
        assertEquals("work", ClaudeLogins(storeDir = tmp.resolve("store")).selected())
        assertTrue(lines.single().contains("'work'"), "$lines")
    }

    @Test
    fun `splice login on the Claude head refuses while one of its sessions runs, naming it`(
        @TempDir tmp: Path,
    ) {
        val configDir = signedIn(tmp)
        session(tmp, "fix the parser", port = 3104)
        val lines = mutableListOf<String>()

        val ok = claudeLabel(tmp, lines).login("claude-splice", claudeHeads(configDir), "work", discard = false)

        assertFalse(ok)
        assertTrue(lines.single().contains("'fix the parser'"), "$lines")
        assertFalse(Files.exists(tmp.resolve("store/work.credentials.json")), "nothing was saved")
    }
}

private const val OAUTH_HEAD_TOML = """
[daemon]
control_port = 3096

[providers.codex]
dialect = "openai-responses"
base_url = "https://example.invalid"
auth = { kind = "chatgpt-oauth" }
[[providers.codex.models]]
id = "gpt-test"
context_window = 200000

[heads.codex]
provider = "codex"
port = 3101
discovery_prefix = "claude-codex--"
pinned_model = "gpt-test"
"""

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
