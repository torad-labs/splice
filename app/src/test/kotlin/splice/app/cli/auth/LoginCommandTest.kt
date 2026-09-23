package splice.app.cli.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.LoginIo
import splice.app.auth.LoginOutput
import splice.client.login.LoginOutcomeFile
import splice.core.config.StatePaths
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.provider.muse.MuseMintAttempt
import splice.provider.muse.MuseMintMode
import splice.provider.muse.MuseSubscriptionKey
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class LoginCommandTest {

    private val loginIo = LoginIo(LoginOutput {})

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
    fun `automatic account receipt names the persisted label instead of auto`(@TempDir tmp: Path) {
        val primary = tmp.resolve("kimi.json")
        Files.writeString(primary, "{}")
        val account = requireNotNull(LoginKimi().spec("kimi", primary, "auto").account)
        assertTrue(loginIo.persistIfSignedIn(primary, """{"access_token":"kimi-secret"}""", account))
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.toString())
        try {
            CliSignIn().writeLoginOutcome("kimi", ok = true, account = account)
            val receipt = requireNotNull(LoginOutcomeFile.consume(StatePaths().stateDir, "kimi"))
            assertTrue(receipt.contains("signed in as 'kimi-2'"), receipt)
            assertFalse(receipt.contains("'auto'"), receipt)
        } finally {
            System.setProperty("user.home", savedHome)
        }
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

    @Test
    fun `muse spec keeps access token and schema and mints after persist`(@TempDir tmp: Path) {
        val primary = tmp.resolve("muse.json")
        Files.writeString(primary, "{}")
        val granted = MuseMintAttempt.Granted(
            MuseSubscriptionKey(
                "minted-key-fake",
                buildJsonObject { put("user_email", JsonPrimitive("ops@example.invalid")) },
            ),
        )
        val muse = LoginMuse { _, mode ->
            assertEquals(MuseMintMode.ONBOARD, mode)
            granted
        }
        val spec = muse.spec("claude-muse", primary)
        assertEquals("1031625952748946", spec.clientId)
        assertTrue(spec.identityHeaders.isEmpty())
        assertEquals("{}", spec.toAuthJson("{}"))
        val written = spec.toAuthJson(
            """{"access_token":"acct-token-fake","token_type":"Bearer","schema":"v1"}""",
        )
        assertTrue(written.contains("acct-token-fake"))
        assertTrue(written.contains("\"schema\""))
        assertTrue(loginIo.persistIfSignedIn(primary, written, spec.account))
        kotlinx.coroutines.runBlocking { spec.afterPersist(primary, spec.account) }
        val onDisk = Json.parseToJsonElement(Files.readString(primary)).jsonObject
        assertEquals("acct-token-fake", onDisk.getValue("access_token").jsonPrimitive.content)
        assertEquals("minted-key-fake", onDisk.getValue("api_key").jsonPrimitive.content)
    }

    @Test
    fun `muse failed mint leaves a valid-but-unminted credential`(@TempDir tmp: Path) {
        val path = tmp.resolve("muse-unminted.json")
        val spec = LoginMuse { _, _ -> MuseMintAttempt.Denied("no-key") }.spec("claude-muse", path)
        assertTrue(loginIo.persistIfSignedIn(path, """{"access_token":"acct-token-fake"}""", spec.account))
        kotlinx.coroutines.runBlocking { spec.afterPersist(path, spec.account) }
        val unminted = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals("acct-token-fake", unminted.getValue("access_token").jsonPrimitive.content)
        assertEquals(null, unminted["api_key"])
    }

    @Test
    fun `muse labels reserve like kimi`(@TempDir tmp: Path) {
        val primary = tmp.resolve("muse.json")
        Files.writeString(primary, "{}")
        val explicit = LoginMuse().spec("claude-muse", primary, "work")
        try {
            val automatic = LoginMuse().spec("claude-muse", primary, "auto")
            try {
                assertEquals("work", requireNotNull(explicit.account).resolvedLabel())
                assertEquals("muse-2", requireNotNull(automatic.account).resolvedLabel())
            } finally {
                automatic.account?.releaseReservation()
            }
        } finally {
            explicit.account?.releaseReservation()
        }
    }

    @Test
    fun `example config muse head has no extra headers and uses port 3106`() {
        val toml = checkNotNull(javaClass.getResourceAsStream("/splice.example.toml")) {
            "example toml missing"
        }.bufferedReader().use { it.readText() }
        val topology = TopologyLoader.parse(toml)
        val head = topology.heads.getValue("claude-muse")
        val muse = topology.providers.getValue(head.provider)
        assertEquals("muse-oauth", muse.auth.kind)
        assertEquals(Dialect.ANTHROPIC_PASSTHROUGH, muse.dialect)
        assertTrue(muse.staticHeaders.isEmpty())
        assertEquals(3106, head.port)
        assertEquals("claude-muse--", head.discoveryPrefix)
        assertEquals(null, head.models)
        muse.catalogFor(head)
    }

    @Test
    fun `kimi and muse device forms are wire-identical aside from client id`(@TempDir tmp: Path) {
        val kimiPath = tmp.resolve("kimi.json")
        val musePath = tmp.resolve("muse.json")
        Files.writeString(kimiPath, "{}")
        Files.writeString(musePath, "{}")
        val kimi = LoginKimi().spec("kimi", kimiPath)
        val muse = LoginMuse().spec("claude-muse", musePath)
        try {
            val placeholder = "CLIENT"
            fun canon(form: String, id: String) = form.replace(id, placeholder)
            assertEquals(
                canon(kimi.deviceAuthForm(kimi.clientId), kimi.clientId),
                canon(muse.deviceAuthForm(muse.clientId), muse.clientId),
            )
            assertEquals(
                canon(kimi.tokenPollForm("DEV123", kimi.clientId), kimi.clientId),
                canon(muse.tokenPollForm("DEV123", muse.clientId), muse.clientId),
            )
            assertEquals(1800L, kimi.parseDeviceAuth("""{"user_code":"A","device_code":"B"}""").expiresInS)
            assertEquals(600L, muse.parseDeviceAuth("""{"user_code":"A","device_code":"B"}""").expiresInS)
        } finally {
            kimi.account?.releaseReservation()
            muse.account?.releaseReservation()
        }
    }

    @Test
    fun `muse login-end mint stays ONBOARD on relogin`(@TempDir tmp: Path) {
        val path = tmp.resolve("muse.json")
        Files.writeString(path, """{"access_token":"old","api_key":"held-key"}""")
        val modes = mutableListOf<MuseMintMode>()
        val spec = LoginMuse { _, mode ->
            modes += mode
            MuseMintAttempt.Denied("held")
        }.spec("claude-muse", path)
        assertTrue(loginIo.persistIfSignedIn(path, """{"access_token":"acct-token-fake"}""", spec.account))
        kotlinx.coroutines.runBlocking { spec.afterPersist(path, spec.account) }
        assertEquals(listOf(MuseMintMode.ONBOARD), modes)
    }

    @Test
    fun `muse labeled pool mint writes the key beside the untouched primary`(@TempDir tmp: Path) {
        val primary = tmp.resolve("muse.json")
        Files.writeString(primary, """{"access_token":"primary-secret"}""")
        val granted = MuseMintAttempt.Granted(
            MuseSubscriptionKey("pool-key-fake", buildJsonObject { }),
        )
        val spec = LoginMuse { _, mode ->
            assertEquals(MuseMintMode.ONBOARD, mode)
            granted
        }.spec("claude-muse", primary, "work")
        try {
            assertTrue(
                loginIo.persistIfSignedIn(primary, """{"access_token":"backup-acct"}""", spec.account),
            )
            kotlinx.coroutines.runBlocking { spec.afterPersist(primary, spec.account) }
            assertEquals("""{"access_token":"primary-secret"}""", Files.readString(primary))
            val pooled = tmp.resolve("muse-oauth/muse.json/work.json")
            val onDisk = Json.parseToJsonElement(Files.readString(pooled)).jsonObject
            assertEquals("backup-acct", onDisk.getValue("access_token").jsonPrimitive.content)
            assertEquals("pool-key-fake", onDisk.getValue("api_key").jsonPrimitive.content)
        } finally {
            spec.account?.releaseReservation()
        }
    }

    @Test
    fun `a throwing muse mint after persist does not fail the login`(@TempDir tmp: Path) {
        val path = tmp.resolve("muse.json")
        val spec = LoginMuse { _, _ -> error("mint exploded") }.spec("claude-muse", path)
        assertTrue(loginIo.persistIfSignedIn(path, """{"access_token":"acct-token-fake"}""", spec.account))
        assertDoesNotThrow {
            kotlinx.coroutines.runBlocking { spec.afterPersist(path, spec.account) }
        }
        val onDisk = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals("acct-token-fake", onDisk.getValue("access_token").jsonPrimitive.content)
        assertNull(onDisk["api_key"])
    }

    @Test
    fun `muse pre-mint bailouts print a reason`(@TempDir tmp: Path) {
        val missing = tmp.resolve("missing-muse.json")
        val spec = LoginMuse { _, _ -> error("must not mint") }.spec("claude-muse", missing)
        val missingOut = java.io.ByteArrayOutputStream()
        val saved = System.out
        System.setOut(java.io.PrintStream(missingOut))
        try {
            assertDoesNotThrow {
                kotlinx.coroutines.runBlocking { spec.afterPersist(missing, spec.account) }
            }
        } finally {
            System.setOut(saved)
        }
        assertTrue(
            missingOut.toString().contains("splice: muse key mint skipped: credential missing after login"),
            missingOut.toString(),
        )
        val empty = tmp.resolve("empty-muse.json")
        Files.writeString(empty, """{"token_type":"Bearer"}""")
        val emptyOut = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(emptyOut))
        try {
            assertDoesNotThrow {
                kotlinx.coroutines.runBlocking { spec.afterPersist(empty, spec.account) }
            }
        } finally {
            System.setOut(saved)
        }
        assertTrue(
            emptyOut.toString().contains("splice: muse key mint skipped: account token missing"),
            emptyOut.toString(),
        )
    }

    @Test
    fun `muse spec parse of a body without expires_in uses 600s`(@TempDir tmp: Path) {
        val spec = LoginMuse().spec("claude-muse", tmp.resolve("muse.json"))
        try {
            val auth = spec.parseDeviceAuth("""{"user_code":"A","device_code":"B"}""")
            assertEquals(600L, auth.expiresInS)
        } finally {
            spec.account?.releaseReservation()
        }
    }

    @Test
    fun `an IOException from muse mint after persist does not fail the login`(@TempDir tmp: Path) {
        val path = tmp.resolve("muse.json")
        val spec = LoginMuse { _, _ -> throw java.io.IOException("disk") }.spec("claude-muse", path)
        assertTrue(loginIo.persistIfSignedIn(path, """{"access_token":"acct-token-fake"}""", spec.account))
        assertDoesNotThrow {
            kotlinx.coroutines.runBlocking { spec.afterPersist(path, spec.account) }
        }
        val onDisk = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals("acct-token-fake", onDisk.getValue("access_token").jsonPrimitive.content)
        assertNull(onDisk["api_key"])
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
