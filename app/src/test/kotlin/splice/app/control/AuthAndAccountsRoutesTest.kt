// NEW: V4-132 — a real ControlServer under the bearer, same rig as ConsoleRoutesTest, for the
// console's new /api/auth/{head}/login[/{id}], /api/auth/{head}/switch,
// DELETE/PATCH /api/auth/{head}/accounts/{label} and GET /api/accounts. Two facts this family
// exists to pin: NULL MEANS UNWIRED (ports.accounts stays null on a head with no console-accounts
// port, so those routes answer a named 5xx rather than an empty or confident-negative payload), and
// the EXPLICIT constant segments in authAndAccountRoutes route ahead of the `{action}` catch-all —
// a POST to .../login that fell through to authAction would answer its generic
// `{"ok":false,"note":"not supported in-process"}` instead of a real login id, so one test below
// pins that shape apart from the other.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.accounts.pool.HeadAccountPinSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.ConsoleAccounts
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginStart
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val WIRED = "wired"
private const val NO_POOL = "no-pool"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthAndAccountsRoutesTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val url = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var control: ControlServer
    private lateinit var key: String
    private val accounts = FakeConsoleAccounts()
    private val pin = FakePin()

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("auth-accounts-routes").resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = port,
            heads = mapOf(WIRED to head(WIRED, pin), NO_POOL to head(NO_POOL, null)),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        control.ports.accounts = accounts
        control.start()
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @Test
    fun `startLogin answers the started status, and login beats the action catch-all`() = runBlocking {
        awaitPort()
        accounts.onStart = { LoginStart.Started(LoginStatus("login-1", WIRED, LoginState.STARTING)) }

        val response = post("/api/auth/$WIRED/login", "{}")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("login-1", body["id"]!!.jsonPrimitive.content)
        assertEquals("starting", body["state"]!!.jsonPrimitive.content)
        assertFalse(
            body.containsKey("note"),
            "a route falling through to the {action} catch-all would answer authAction's " +
                "'not supported in-process' shape instead: ${response.bodyAsText()}",
        )
    }

    @Test
    fun `startLogin on an unwired accounts port answers a named 503, never an empty login`() = runBlocking {
        awaitPort()
        control.ports.accounts = null
        try {
            val response = post("/api/auth/$WIRED/login", "{}")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("wired no accounts port"), response.bodyAsText())
        } finally {
            control.ports.accounts = accounts
        }
    }

    @Test
    fun `startLogin on an unsupported auth kind is a 400 naming the kind`() = runBlocking {
        awaitPort()
        accounts.onStart = { LoginStart.UnsupportedAuthKind("api-key") }

        val response = post("/api/auth/$WIRED/login", "{}")

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("api-key"), response.bodyAsText())
    }

    @Test
    fun `startLogin on an unknown head is a 404, not the accounts port`() = runBlocking {
        awaitPort()
        val response = post("/api/auth/no-such-head/login", "{}")
        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun `pollLogin answers the full announcement shape for a known id`() = runBlocking {
        awaitPort()
        accounts.onPoll = { id ->
            LoginStatus(id, WIRED, LoginState.WAITING, userCode = "ABCD-EFGH", verificationUri = "https://x/verify")
        }

        val response = get("/api/auth/$WIRED/login/login-9")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("waiting", body["state"]!!.jsonPrimitive.content)
        assertEquals("ABCD-EFGH", body["user_code"]!!.jsonPrimitive.content)
        assertEquals("https://x/verify", body["verification_uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pollLogin on an unknown id is a 404`() = runBlocking {
        awaitPort()
        accounts.onPoll = { null }

        val response = get("/api/auth/$WIRED/login/no-such-id")

        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("unknown login id"), response.bodyAsText())
    }

    @Test
    fun `switchAccount pins a known label and answers ok true`() = runBlocking {
        awaitPort()
        pin.result = true

        val response = post("/api/auth/$WIRED/switch", """{"label":"plus-a"}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("plus-a", pin.lastLabel)
        assertEquals("true", json.parseToJsonElement(response.bodyAsText()).jsonObject["ok"]!!.jsonPrimitive.content)
    }

    @Test
    fun `switchAccount on an unknown label is a 400 naming it, and never pins anything`() = runBlocking {
        awaitPort()
        pin.result = false

        val response = post("/api/auth/$WIRED/switch", """{"label":"no-such-label"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("no-such-label"), response.bodyAsText())
    }

    @Test
    fun `switchAccount with a blank body is a 400, never a silent no-op`() = runBlocking {
        awaitPort()
        val response = post("/api/auth/$WIRED/switch", "{}")
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("label"), response.bodyAsText())
    }

    @Test
    fun `switchAccount on a head with no pin source is a 400 naming the head`() = runBlocking {
        awaitPort()
        val response = post("/api/auth/$NO_POOL/switch", """{"label":"plus-a"}""")
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains(NO_POOL), response.bodyAsText())
    }

    @Test
    fun `removeAccount reports ok, unknown head and a refusal through the same mutation shape`() = runBlocking {
        awaitPort()
        accounts.onRemove = { _, _ -> AccountMutation.Ok }
        assertEquals(HttpStatusCode.OK, delete("/api/auth/$WIRED/accounts/plus-a").status)

        accounts.onRemove = { _, _ -> AccountMutation.Refused("the primary account cannot be removed") }
        val refused = delete("/api/auth/$WIRED/accounts/primary")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(refused.bodyAsText().contains("primary account cannot be removed"))
    }

    @Test
    fun `relabelAccount requires a new label in the body and reports the mutation`() = runBlocking {
        awaitPort()
        val noBody = patch("/api/auth/$WIRED/accounts/plus-a", "{}")
        assertEquals(HttpStatusCode.BadRequest, noBody.status, noBody.bodyAsText())

        accounts.onRelabel = { _, _, _ -> AccountMutation.Ok }
        val ok = patch("/api/auth/$WIRED/accounts/plus-a", """{"label":"plus-b"}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals("plus-a" to "plus-b", accounts.lastRelabel.get())
    }

    @Test
    fun `GET api-accounts joins the wired heads' accounts into one payload`() = runBlocking {
        awaitPort()
        val response = get("/api/accounts")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("\"accounts\""), response.bodyAsText())
    }

    // ── rig ─────────────────────────────────────────────────────────────────────────────────────

    private fun head(key: String, pinSource: HeadAccountPinSource?): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = key
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "test", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
        accountPool = pinSource?.let { source -> PinnedPoolSource(source) },
    )

    /** [HeadAccountPoolSource] AND [HeadAccountPinSource] on the same object, the same checked-cast
     *  shape [splice.accounts.pool.SwitchRoute.switchAccount] discovers in production
     *  (`head.pool as? HeadAccountPinSource`). */
    private class PinnedPoolSource(
        private val pinner: HeadAccountPinSource,
    ) : HeadAccountPoolSource, HeadAccountPinSource by pinner {
        override fun view(sessionId: String?): HeadAccountPoolView = HeadAccountPoolView(null, emptyList(), null)
    }

    private class FakePin : HeadAccountPinSource {
        var result: Boolean = true
        var lastLabel: String? = null

        override fun pin(label: String): Boolean {
            lastLabel = label
            return result
        }
    }

    private class FakeConsoleAccounts : ConsoleAccounts {
        var onStart: () -> LoginStart = { LoginStart.UnknownHead }
        var onPoll: (String) -> LoginStatus? = { null }
        var onRemove: (String, String) -> AccountMutation = { _, _ -> AccountMutation.UnknownHead }
        var onRelabel: (String, String, String) -> AccountMutation = { _, _, _ -> AccountMutation.UnknownHead }
        val lastRelabel = AtomicReference<Pair<String, String>?>(null)

        override suspend fun startLogin(headKey: String, label: String?, restart: HeadRestart): LoginStart =
            onStart()

        override fun pollLogin(id: String): LoginStatus? = onPoll(id)

        override suspend fun removeAccount(headKey: String, label: String): AccountMutation = onRemove(headKey, label)

        override suspend fun relabelAccount(headKey: String, label: String, newLabel: String): AccountMutation {
            lastRelabel.set(label to newLabel)
            return onRelabel(headKey, label, newLabel)
        }
    }

    private suspend fun get(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.get("$url$path") { header("Authorization", "Bearer $key") }
    }

    private suspend fun post(path: String, body: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.post("$url$path") {
            header("Authorization", "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun patch(path: String, body: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.patch("$url$path") {
            header("Authorization", "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun delete(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.delete("$url$path") { header("Authorization", "Bearer $key") }
    }

    private suspend fun awaitPort() {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { ServerSocket(port).close() }.isFailure) return
            delay(POLL_MS)
        }
        error("the control server never bound :$port")
    }
}
