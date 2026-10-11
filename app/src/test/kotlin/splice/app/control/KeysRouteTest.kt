// NEW: V4-220 item 3 — GET/PUT/DELETE /api/keys, the console's `splice key list|set|unset`, driven
// through a real ControlServer under the bearer. The heads are real ApiKeyAuthProviders over a temp
// keys.toml, one reading the store and one whose environment sets its variable, so every answer is
// checked against what those providers now read, not against the write having returned.
//
// THE VALUE GOES ONE WAY: every response body and every line any sink in this daemon logged is
// searched for the value, and for every 8-character window of it (the masked form /api/auth prints
// keeps 4 + 4 apart, never 8 together), after it was stored. A replace-only route that logged its body
// would leak the secret anyway.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.KeyStore
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.provider.openai.ApiKeyAuthProvider
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val WINDOW = 8
private const val STORED = "TEST_ROUTER_API_KEY"
private const val SHADOWED = "TEST_SHADOWED_API_KEY"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeysRouteTest {

    private val port: Int get() = control.listeningPort
    private val url: String get() = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var control: ControlServer
    private lateinit var key: String
    private lateinit var store: KeyStore

    /** Every line any sink in this daemon wrote: the control plane's, the store's, the providers'. */
    private val logged = CopyOnWriteArrayList<String>()
    private val log = LogSink { logged += it }

    /** Every response body this test read. */
    private val bodies = CopyOnWriteArrayList<String>()

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        store = KeyStore(tmp.resolve("config/splice/keys.toml"), log)
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        val daemonEnv = EnvReader { name -> if (name == SHADOWED) "set-in-the-daemon-environment" else null }
        control = controlServerFor(
            port = 0,
            heads = mapOf(
                "router" to managedHead("router", ApiKeyAuthProvider(STORED, null, EnvReader { null }, store, log)),
                "shadowed" to managedHead("shadowed", ApiKeyAuthProvider(SHADOWED, null, daemonEnv, store, log)),
            ),
            config = ConfigService(paths),
            auth = ControlAuth(mgmtKey = mgmt, log = log),
        )
        control.ports.keys = store
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @Test
    fun `a stored key is the one its head reads, the value never comes back or reaches a log`() = runBlocking<Unit> {
        awaitPort()
        val secret = "sk-test-" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID()

        val put = send("PUT", "/api/keys/$STORED", """{"value":"$secret"}""")
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val applied = obj(put)
        assertTrue(applied["stored"]!!.jsonPrimitive.boolean, "$applied")
        assertEquals(listOf("router" to "store"), readers(applied), "the head reads the stored key now")
        assertEquals(secret, store.read(STORED), "the value landed in keys.toml")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(store.path),
        )

        val listed = obj(send("GET", "/api/keys")).getValue("keys").jsonArray.map { it.jsonObject }
        val row = listed.single { it["name"]!!.jsonPrimitive.content == STORED }
        assertTrue(row["stored"]!!.jsonPrimitive.boolean, "$row")
        send("GET", "/api/auth")

        val removed = send("DELETE", "/api/keys/$STORED")
        assertEquals(HttpStatusCode.OK, removed.status, removed.bodyAsText())
        assertFalse(obj(removed)["stored"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("router" to "missing"), readers(obj(removed)), "nothing supplies it once removed")

        assertTrue(logged.any { it.contains("keys: stored $STORED") }, "the write is logged by name: $logged")
        assertNoValueBytes(secret)
    }

    // Oct 10, 2026, console BUILD row "a key is its own account": a replaced key is a new account with its own date,
    // and the old one stays listed as replaced, by a fingerprint that is not the key.
    @Test
    fun `a replaced key keeps its fingerprint and first date, listed after the key that replaced it`() =
        runBlocking<Unit> {
            awaitPort()
            val first = "sk-test-" + UUID.randomUUID().toString().replace("-", "")
            val second = "sk-test-" + UUID.randomUUID().toString().replace("-", "")
            val a = obj(send("PUT", "/api/keys/$STORED", """{"value":"$first"}"""))
            val b = obj(send("PUT", "/api/keys/$STORED", """{"value":"$second"}"""))
            val fa = a["fingerprint"]!!.jsonPrimitive.content
            val fb = b["fingerprint"]!!.jsonPrimitive.content
            assertTrue(fa != fb, "two keys, two fingerprints")
            val replaced = b.getValue("replaced").jsonArray.map { it.jsonObject }
            assertEquals(fa, replaced.first()["fingerprint"]!!.jsonPrimitive.content, "newest replaced key first")
            assertEquals(a["first_seen_epoch_seconds"], replaced.first()["first_seen_epoch_seconds"])

            val back = obj(send("PUT", "/api/keys/$STORED", """{"value":"$first"}"""))
            assertEquals(fa, back["fingerprint"]!!.jsonPrimitive.content)
            val day = a["first_seen_epoch_seconds"]
            assertEquals(day, back["first_seen_epoch_seconds"], "a key seen before keeps its day")
            val newest = back.getValue("replaced").jsonArray.first().jsonObject
            assertEquals(fb, newest["fingerprint"]!!.jsonPrimitive.content)

            val ledger = store.path.resolveSibling("key-ledger.json")
            bodies += Files.readString(ledger)
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(ledger),
            )
            send("DELETE", "/api/keys/$STORED")
            assertNoValueBytes(first)
            assertNoValueBytes(second)
        }

    @Test
    fun `a key the daemon's environment sets reads as shadowed, never as applied`() = runBlocking<Unit> {
        awaitPort()
        val put = send("PUT", "/api/keys/$SHADOWED", """{"value":"sk-test-${UUID.randomUUID()}"}""")
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals(listOf("shadowed" to "environment"), readers(obj(put)), "the environment wins over the store")
        send("DELETE", "/api/keys/$SHADOWED")
    }

    // Review of b274f3dd2..a0583a85a, finding 10: a ledger splice cannot write threw out of the list route.
    @Test
    fun `a read-only config folder still lists its keys, with the day a key was first seen unknown`() =
        runBlocking<Unit> {
            awaitPort()
            val folder = store.path.parent
            Files.createDirectories(folder)
            Files.deleteIfExists(folder.resolve("key-ledger.json"))
            val open = Files.getPosixFilePermissions(folder)
            val readOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
            Files.setPosixFilePermissions(folder, readOnly)
            try {
                val list = send("GET", "/api/keys")
                assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
                val shadowed = obj(list).getValue("keys").jsonArray.map { it.jsonObject }
                    .single { it["name"]!!.jsonPrimitive.content == SHADOWED }
                assertTrue(shadowed["fingerprint"]!!.jsonPrimitive.content.isNotEmpty(), "the key in use still reads")
                assertEquals(JsonNull, shadowed["first_seen_epoch_seconds"], "no day splice could not record")
                assertTrue(logged.any { "could not be written" in it }, "and the log says why")
            } finally {
                Files.setPosixFilePermissions(folder, open)
            }
        }

    @Test
    fun `refusals carry their reason and the status that matches`() = runBlocking<Unit> {
        awaitPort()
        val badName = send("PUT", "/api/keys/lower_case", """{"value":"x"}""")
        assertRefused(badName, HttpStatusCode.BadRequest, "not an environment variable name")
        val blank = send("PUT", "/api/keys/$STORED", """{"value":"  "}""")
        assertRefused(blank, HttpStatusCode.BadRequest, "non-empty 'value'")
        assertRefused(send("PUT", "/api/keys/$STORED", "not json"), HttpStatusCode.BadRequest, "non-empty 'value'")
        val lineBreak = send("PUT", "/api/keys/$STORED", """{"value":"a\nb"}""")
        assertRefused(lineBreak, HttpStatusCode.BadRequest, "line break")
        assertRefused(send("DELETE", "/api/keys/NEVER_STORED_KEY"), HttpStatusCode.NotFound, "was not stored")
        assertEquals(null, store.read(STORED), "no refused write reached the store")
    }

    @Test
    fun `an unreadable store refuses the write and keeps what it holds`() = runBlocking<Unit> {
        awaitPort()
        Files.createDirectories(store.path.parent)
        Files.writeString(store.path, "$STORED = \"kept\"\n")
        Files.setPosixFilePermissions(store.path, emptySet())
        try {
            val put = send("PUT", "/api/keys/$STORED", """{"value":"replacement-value"}""")
            assertRefused(put, HttpStatusCode.Conflict, "refusing to write")
        } finally {
            Files.setPosixFilePermissions(
                store.path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        assertEquals("kept", store.read(STORED))
        send("DELETE", "/api/keys/$STORED")
    }

    @Test
    fun `the key routes are the management key's`() = runBlocking<Unit> {
        awaitPort()
        val anonymous = client.put("$url/api/keys/$STORED") { setBody("""{"value":"x"}""") }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals(null, store.read(STORED))
    }

    private fun assertNoValueBytes(secret: String) {
        val windows = secret.windowed(WINDOW)
        (bodies + logged).forEach { text ->
            val leaked = windows.firstOrNull { it in text }
            assertEquals(null, leaked, "value bytes '$leaked' in: $text")
        }
    }

    private suspend fun assertRefused(response: HttpResponse, status: HttpStatusCode, reason: String) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        val error = json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content
        assertTrue(error.contains(reason), error)
        assertFalse(error.contains('\n'), "a refusal is one line: $error")
    }

    private fun readers(applied: JsonObject): List<Pair<String, String>> =
        applied.getValue("heads").jsonArray.map {
            it.jsonObject["head"]!!.jsonPrimitive.content to it.jsonObject["source"]!!.jsonPrimitive.content
        }

    private suspend fun obj(response: HttpResponse): JsonObject =
        json.parseToJsonElement(response.bodyAsText()).jsonObject

    private suspend fun send(
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse = withTimeout(TIMEOUT_MS) {
        val response = when (method) {
            "PUT" -> client.put("$url$path") {
                header("Authorization", "Bearer $key")
                setBody(body.orEmpty())
            }
            "DELETE" -> client.delete("$url$path") { header("Authorization", "Bearer $key") }
            else -> client.get("$url$path") { header("Authorization", "Bearer $key") }
        }
        bodies += response.bodyAsText()
        response
    }

    private fun managedHead(name: String, auth: AuthProvider): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = name
            override val label: String = name
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = auth,
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
    )

    private suspend fun awaitPort() {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { ServerSocket(port).close() }.isFailure) return
            delay(POLL_MS)
        }
        error("the control server never bound :$port")
    }
}
