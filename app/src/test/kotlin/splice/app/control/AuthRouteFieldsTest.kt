// `GET /api/auth` answers with the fields the console and the checkup read and nothing a person could not share:
// a sign-in's file path, a key file's path and a read failure that quotes one stay out, and an API key shows only
// its masked form. The route is driven over HTTP with an OAuth login that describes itself as the real ones do and
// a real API-key provider holding a key.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.KeyStore
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.util.EnvReader
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.provider.openai.ApiKeyAuthProvider
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.nio.file.Path

private const val OAUTH = "oauth"
private const val KEYED = "keyed"
private const val SECRET = "sk-test-0123456789abcdef"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRouteFieldsTest {

    private val client = HttpClient(CIO)
    private lateinit var control: ControlServer
    private lateinit var key: String

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        val oauth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(
                present = true,
                kind = "chatgpt-oauth",
                fields = mapOf(
                    "auth_path" to "/home/someone/.config/splice/auth/codex.json",
                    "account_ref" to "1a2b3c4d",
                    "last_refresh" to "2026-10-12T10:00:00Z",
                    "read_error" to "NoSuchFileException: /home/someone/.codex/auth.json",
                ),
            )
        }
        val keyed = ApiKeyAuthProvider(
            envVar = "TEST_PROVIDER_API_KEY",
            keyFile = tmp.resolve("key-file"),
            envReader = EnvReader { if (it == "TEST_PROVIDER_API_KEY") SECRET else null },
            keyStore = KeyStore(tmp.resolve("keys.toml")),
        )
        control = controlServerFor(
            port = 0,
            heads = mapOf(OAUTH to head(OAUTH, oauth), KEYED to head(KEYED, keyed)),
            config = ConfigService(paths),
            auth = ControlAuth(mgmtKey = mgmt, log = { }),
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    private fun answer(): Map<String, Set<String>> = runBlocking {
        val text = client.get("http://127.0.0.1:${control.listeningPort}/api/auth") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonObject.keys }
    }

    private fun reasonOf(head: String): String = runBlocking {
        val text = client.get("http://127.0.0.1:${control.listeningPort}/api/auth") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        Json.parseToJsonElement(text).jsonObject.getValue(head).jsonObject.getValue("read_error").jsonPrimitive.content
    }

    @Test
    fun `an oauth login answers with its reference and refresh time, never its path, and a read failure as words`() {
        assertEquals(
            setOf(
                "kind",
                "login",
                "present",
                "verdict",
                "account_ref",
                "last_refresh",
                "read_error",
            ),
            answer().getValue(OAUTH),
        )
        val reason = reasonOf(OAUTH)
        assertEquals("can't read the login file", reason, "the reason stays, without the provider's text or path")
    }

    @Test
    fun `an api-key login answers with its variable, source and masked key, never the key or its file`() {
        assertEquals(
            setOf(
                "kind",
                "login",
                "present",
                "verdict",
                "env_var",
                "key_source",
                "api_key_masked",
                "key_fingerprint",
            ),
            answer().getValue(KEYED),
        )
        val body = runBlocking {
            client.get("http://127.0.0.1:${control.listeningPort}/api/auth") { header("Authorization", "Bearer $key") }
                .bodyAsText()
        }
        assertFalse(body.contains(SECRET), "the key itself is never in the answer")
        assertFalse(body.contains("key-file"), "the key file's path is never in the answer")
    }

    private fun head(key: String, provider: AuthProvider): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = key
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = provider,
        sources = HeadSources(
            usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
            },
            logs = object : HeadLogSource {
                override fun tail(lines: Int): String = ""
                override fun path(): String = ""
            },
        ),
        usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
        authSurface = HeadAuthSurface(accountPool = null),
    )
}
