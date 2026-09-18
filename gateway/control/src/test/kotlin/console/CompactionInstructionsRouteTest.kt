// NEW: V4-136 — the wired route test, through a real ControlServer under the bearer.
//
// The pin the ruling asked for by name is the UNSET case: an unwired daemon must answer a NAMED
// failure, never an empty scope list, because an empty list tells an operator that nothing is
// configured while the daemon compacts with rules. Reaching that branch needs a head that EXISTS —
// the unknown-head guard runs first — so the rig assembles one, because a test that cannot reach the
// branch it names is the vacuous green this campaign keeps finding.
//
// The rig mirrors EventsRouteTest: a real server on a real port, so a route registered outside the
// guard or under the wrong path fails here rather than in production.
package console

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import splice.control.CompactView
import splice.control.ControlServer
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadUsageSource
import splice.control.ManagedHead
import splice.control.RateLimitView
import splice.control.UsageView
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.compaction.CompactionConfig
import splice.core.compaction.CompactionInstructions
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import java.net.ServerSocket
import java.nio.file.Files

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val HEAD_KEY = "claude"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactionInstructionsRouteTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val url = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var control: ControlServer
    private lateinit var key: String

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("compaction-route").resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = port,
            heads = mapOf(HEAD_KEY to managedHead()),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        control.start()
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @Test
    fun `the route needs the bearer`() = runBlocking {
        awaitPort()
        val refused = withTimeout(TIMEOUT_MS) { client.get("$url/api/compaction/instructions?head=$HEAD_KEY") }
        assertEquals(HttpStatusCode.Unauthorized, refused.status, "an unguarded inspection route leaks config shape")
    }

    @Test
    fun `an unwired daemon answers a named failure, never an empty list`() = runBlocking {
        awaitPort()
        control.compaction = null
        val response = get(HEAD_KEY)
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertTrue(
            body.contains("did not wire the compaction table"),
            "the failure must SAY the daemon did not wire it: $body",
        )
        assertFalse(body.contains("\"scopes\""), "an unwired daemon must not answer a scope list at all: $body")
    }

    @Test
    fun `an unknown head is a 400 naming it, never a 404`() = runBlocking {
        awaitPort()
        control.compaction = CompactionInstructions(config = CompactionConfig(instructions = "x"), log = { })
        val response = get("no-such-head")
        assertEquals(
            HttpStatusCode.BadRequest,
            response.status,
            "the console reads 404 on this path as route-not-built, so an unknown head must be 400",
        )
        assertTrue(response.bodyAsText().contains("no-such-head"), response.bodyAsText())
    }

    @Test
    fun `a wired daemon reports the scope, the source label and a live length, never the text`() = runBlocking {
        awaitPort()
        control.compaction = CompactionInstructions(config = CompactionConfig(instructions = "global text"), log = { })
        val response = get(HEAD_KEY)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val scopes = json.parseToJsonElement(response.bodyAsText()).jsonObject["scopes"]!!.jsonArray
        assertEquals(1, scopes.size, "the fixture configures exactly one rule")
        val entry = scopes[0].jsonObject
        assertEquals("global", entry["scope"]!!.jsonPrimitive.content)
        assertEquals("global", entry["source"]!!.jsonPrimitive.content)
        assertEquals(11, entry["chars"]!!.jsonPrimitive.int, "len(global text)")
        assertFalse(entry.containsKey("text"), "no instruction text may cross the wire")
    }

    private suspend fun get(head: String) = withTimeout(TIMEOUT_MS) {
        client.get("$url/api/compaction/instructions?head=$head") {
            header("Authorization", "Bearer $key")
        }
    }

    private fun managedHead(): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = HEAD_KEY
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
