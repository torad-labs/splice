// NEW: V4-127 — the console routes, through a real ControlServer under the bearer.
//
// ONE RIG FOR THE FAMILY, because the pins that matter are the ones the family SHARES. Every route
// here reads an injected port, and every one of them must answer a NAMED 5xx when that port is
// unwired rather than a payload that reads as a legitimate negative: no tiers declared, nothing
// wrong, nothing to upgrade, no turns recorded. Each of those is a did-not-run
// wearing a legitimate answer, and the payload cannot be told from the real one — which is why every
// unwired test below asserts BOTH the status AND the absence of the key a console would render.
//
// The rig mirrors CompactionInstructionsRouteTest and EventsRouteTest: a real server on a real port,
// so a route registered outside the guard or under the wrong path fails here rather than in
// production. A vacuous green is the failure mode this campaign keeps finding, so each unwired test
// sets its own port to null in the test body rather than relying on cross-test state.
package console

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
import splice.control.DeclaredHead
import splice.control.DeclaredHeads
import splice.control.DoctorReport
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadUsageSource
import splice.control.ManagedHead
import splice.control.PerfRow
import splice.control.PerfRowsSource
import splice.control.PerfRowsWindow
import splice.control.RateLimitView
import splice.control.UpgradeStatus
import splice.control.UsageView
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.HeadModel
import java.net.ServerSocket
import java.nio.file.Files

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val HEAD_KEY = "claude"

// Five DISTINGUISHABLE values, one per fact the row carries. Distinct on purpose: a reader or a route
// that swapped two of them by position would still pass a fixture whose values were all the same
// shape, and the ModelRates scar (V4-127) is exactly that failure — a positional call that swapped
// two same-typed fields, compiled, passed, and reported the wrong numbers with a green suite.
private const val ROW_MODEL = "row-model"
private const val ROW_SESSION = "row-session"
private const val ROW_ACCOUNT = "row-account"
private const val ROW_OUTCOME = "row-outcome"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleRoutesTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val url = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var control: ControlServer
    private lateinit var key: String

    private val rows = listOf(
        PerfRow(
            ts = 1_000L,
            outcome = ROW_OUTCOME,
            fields = mapOf("total" to 5L),
            model = ROW_MODEL,
            session = ROW_SESSION,
            account = ROW_ACCOUNT,
            cacheCold = true,
            compact = false,
        ),
        // A row with NO account: the writer never wrote cache_cold for it, so the question was never
        // asked and the payload must say so rather than answer false.
        PerfRow(ts = 2_000L, outcome = "ok", fields = mapOf("total" to 7L)),
    )

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("console-routes").resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = port,
            heads = mapOf(HEAD_KEY to managedHead(), "bare" to bareHead()),
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

    // ── the guard, on every route ───────────────────────────────────────────────────────────────

    @Test
    fun `every console route needs the bearer`() = runBlocking {
        awaitPort()
        assertEquals(HttpStatusCode.Unauthorized, plain("/api/perf/turns?head=$HEAD_KEY").status)
        assertEquals(HttpStatusCode.Unauthorized, plain("/api/models").status)
        assertEquals(HttpStatusCode.Unauthorized, plain("/api/doctor").status)
        assertEquals(HttpStatusCode.Unauthorized, plain("/api/upgrade").status)
    }

    // ── GET /api/perf/turns ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown head is a 400 naming it, never a 404`() = runBlocking {
        awaitPort()
        val response = get("/api/perf/turns?head=no-such-head")
        assertEquals(
            HttpStatusCode.BadRequest,
            response.status,
            "the console reads 404 on this path as route-not-built, so an unknown head must be 400",
        )
        assertTrue(response.bodyAsText().contains("no-such-head"), response.bodyAsText())
    }

    @Test
    fun `an absent or unparseable parameter is refused, never replaced by its default`() = runBlocking {
        awaitPort()
        val noHead = get("/api/perf/turns")
        assertEquals(HttpStatusCode.BadRequest, noHead.status, "head is required: ${noHead.bodyAsText()}")
        val badSince = get("/api/perf/turns?head=$HEAD_KEY&since=yesterday")
        assertEquals(
            HttpStatusCode.BadRequest,
            badSince.status,
            "answering the 24h default for a request spelled differently is a different window " +
                "than the one asked for: ${badSince.bodyAsText()}",
        )
        assertTrue(badSince.bodyAsText().contains("yesterday"), badSince.bodyAsText())
        val badN = get("/api/perf/turns?head=$HEAD_KEY&n=many")
        assertEquals(HttpStatusCode.BadRequest, badN.status, badN.bodyAsText())
    }

    @Test
    fun `an unwired head answers a named failure, never an empty turn list`() = runBlocking {
        awaitPort()
        val response = get("/api/perf/turns?head=bare")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertTrue(body.contains("wired no perf row source"), "the failure must SAY why: $body")
        assertFalse(body.contains("\"rows\""), "an unwired head must not list turns at all: $body")
    }

    @Test
    fun `a wired head reports the non-numeric facts and what the window did NOT show`() = runBlocking {
        awaitPort()
        val response = get("/api/perf/turns?head=$HEAD_KEY&since=0&n=1")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val head = json.parseToJsonElement(response.bodyAsText()).jsonObject["heads"]!!.jsonArray[0].jsonObject

        // The clamp is STATED, not silent: the window holds two rows and the newest one was returned.
        assertEquals(2, head["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, head["returned"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, head["truncated"]!!.jsonPrimitive.content.toBoolean())

        val row = head["rows"]!!.jsonArray[0].jsonObject
        assertEquals(2_000L, row["ts"]!!.jsonPrimitive.content.toLong(), "the NEWEST row survives the clamp")
        assertEquals("ok", row["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the facts keep their own values, and an unrecorded flag stays absent`() = runBlocking {
        awaitPort()
        val response = get("/api/perf/turns?head=$HEAD_KEY&since=0&n=200")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val returned = json.parseToJsonElement(response.bodyAsText()).jsonObject["heads"]!!.jsonArray[0]
            .jsonObject["rows"]!!.jsonArray

        val full = returned[0].jsonObject
        assertEquals(ROW_OUTCOME, full["outcome"]!!.jsonPrimitive.content)
        assertEquals(ROW_MODEL, full["model"]!!.jsonPrimitive.content)
        assertEquals(ROW_SESSION, full["session"]!!.jsonPrimitive.content)
        assertEquals(ROW_ACCOUNT, full["account"]!!.jsonPrimitive.content)
        assertEquals(true, full["cache_cold"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            false,
            full["compact"]!!.jsonPrimitive.content.toBoolean(),
            "a recorded false is a fact, and must not read as absent",
        )

        // NOTHING LOOKED, SO NOTHING IS REPORTED. This row carries no account, so the writer never
        // wrote cache_cold. Rendering that as false would claim the cache was warm about a turn where
        // no account was in play — a did-not-run wearing a legitimate answer.
        val bare = returned[1].jsonObject
        assertEquals(JsonNull, bare["cache_cold"], "an unrecorded flag is null, never false")
        assertEquals(JsonNull, bare["model"], "an absent model is null, never an empty string")
        assertEquals(JsonNull, bare["compact"], "an absent compact flag is null, never false")
    }

    // ── GET /api/models ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unwired roster is a named failure, never every model with no tier naming it`() = runBlocking {
        awaitPort()
        control.declaredHeads = null
        val response = get("/api/models")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        val body = response.bodyAsText()
        assertTrue(body.contains("did not wire the declared roster"), "the failure must SAY why: $body")
        assertFalse(body.contains("\"heads\""), "an unwired roster must not serve a head list: $body")
        // And it must be the UNWIRED answer, not the per-head one: a route that collapsed an unwired
        // roster into an empty map would answer the head-naming variant, which is a different fact
        // (the wiring exists but forgot a head) reported as this one.
        assertFalse(body.contains("no entry for head"), "an unwired roster is not a missing head: $body")
    }

    @Test
    fun `a head the roster does not name is a named failure, not a head with nothing declared`() = runBlocking {
        awaitPort()
        control.declaredHeads = DeclaredHeads { mapOf(HEAD_KEY to DeclaredHead("prov", listOf(HeadModel("m1", "fast")))) }
        val response = get("/api/models")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("bare"), "the failure must NAME the head: ${response.bodyAsText()}")
    }

    @Test
    fun `the join runs both ways, so a declared slot that resolved to nothing is its own row`() = runBlocking {
        awaitPort()
        control.declaredHeads = DeclaredHeads {
            mapOf(
                HEAD_KEY to DeclaredHead("prov-a", listOf(HeadModel("m1", "fast"), HeadModel("ghost", "slow"))),
                "bare" to DeclaredHead("prov-b", emptyList()),
            )
        }
        val response = get("/api/models")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val heads = json.parseToJsonElement(response.bodyAsText()).jsonObject["heads"]!!.jsonArray
        // PROVIDER COMES FROM THE ROLE, PER HEAD (FEATURES.md §6: the page groups by provider). Two
        // different values on the two heads, so a route that collapsed them into one value, or read a
        // single shared one, fails here rather than drawing a page grouped under the wrong bay.
        assertEquals("prov-a", heads[0].jsonObject["provider"]!!.jsonPrimitive.content)
        assertEquals("prov-b", heads[1].jsonObject["provider"]!!.jsonPrimitive.content)
        val models = heads[0].jsonObject["models"]!!.jsonArray.map { it.jsonObject }

        val resolved = models.single { it["id"]!!.jsonPrimitive.content == "m1" }
        assertEquals(true, resolved["resolved"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("fast", resolved["slot"]!!.jsonPrimitive.content)

        val unresolved = models.single { it["id"]!!.jsonPrimitive.content == "ghost" }
        assertEquals(
            false,
            unresolved["resolved"]!!.jsonPrimitive.content.toBoolean(),
            "a declared tier the operator will NOT get must be shown, never dropped",
        )
        assertEquals("slow", unresolved["slot"]!!.jsonPrimitive.content)
        assertTrue(unresolved["reason"]!!.jsonPrimitive.content.isNotEmpty(), "and it must say why")

        // THE KEY THE CONSOLE ACTUALLY READS. This route shipped it as `window_source`; the console
        // declares `context_window_source` NON-OPTIONAL in TS, so the mismatch was invisible to the
        // compiler AND to every fixture — the fixtures spelled it the console's way, and two
        // hand-authored artifacts agreed with each other while disagreeing with the daemon. Both rows
        // carry it, so both are pinned: a rename that touches one branch and not the other is the
        // other half of this same defect.
        for (row in listOf(resolved, unresolved)) {
            assertTrue(
                row.containsKey("context_window_source"),
                "the console reads exactly this key, on every row: ${row.keys}",
            )
            assertFalse(row.containsKey("window_source"), "and not the name it shipped with first")
        }
    }

    // ── GET /api/doctor and GET /api/upgrade ────────────────────────────────────────────────────

    @Test
    fun `an unwired doctor port answers a named failure, never an empty report`() = runBlocking {
        awaitPort()
        control.doctor = null
        val response = get("/api/doctor")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("not wired on this daemon"), response.bodyAsText())
        assertErrorOnly(response.bodyAsText())
    }

    @Test
    fun `a wired doctor port serves the report verbatim, unreshaped`() = runBlocking {
        awaitPort()
        // Deliberately a shape this module knows nothing about: whatever the CLI rendered is what must
        // come back, because a second serving layer that reshaped it would be a second redaction
        // policy, and two implementations of redaction disagreeing is worse than none.
        val report = """{"heads":{"claude":{"running":true}},"notes":["a five word note here"]}"""
        control.doctor = DoctorReport { report }
        val response = get("/api/doctor")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(report, response.bodyAsText(), "the route serves the port's own bytes")
    }

    @Test
    fun `an unwired upgrade port answers a named failure, never a current-looking status`() = runBlocking {
        awaitPort()
        control.upgrade = null
        val response = get("/api/upgrade")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("upgrade surface"), response.bodyAsText())
        // NOT AN EMPTY STATUS OBJECT. `installed`, `latest` and `rollback_available` are what the bay
        // renders, and a payload carrying any of them here would read as a daemon that is current.
        assertErrorOnly(response.bodyAsText())
    }

    @Test
    fun `a wired upgrade port serves all six states verbatim`() = runBlocking {
        awaitPort()
        // The FULL six-state payload, including the basis fields and the absolute check time, because
        // the route's one job is to hand the console exactly what the port composed. A serving layer
        // that defaulted `latest_basis` or dropped the null fields would produce a payload that reads
        // as measured when nothing looked — the disagreement would appear precisely when it matters.
        val status = """{"installed":"0.4.0","latest":null,"latest_basis":"unavailable",""" +
            """"latest_unavailable_reason":"no upgrade check has succeeded on this daemon",""" +
            """"rollback_target":null,"rollback_basis":"measured","rollback_unavailable_reason":null,""" +
            """"checked_at_epoch_millis":null}"""
        control.upgrade = UpgradeStatus { status }
        val response = get("/api/upgrade")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(status, response.bodyAsText(), "the route serves the port's own bytes")
        val served = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val named = setOf(
            "installed",
            "latest",
            "latest_basis",
            "latest_unavailable_reason",
            "rollback_target",
            "rollback_basis",
            "rollback_unavailable_reason",
            "checked_at_epoch_millis",
        )
        assertTrue(served.keys.containsAll(named), "missing ${named - served.keys}")
        assertEquals(JsonNull, served["checked_at_epoch_millis"], "null, never zero and never the epoch")
    }

    // ── rig ─────────────────────────────────────────────────────────────────────────────────────

    /** The body is the NAMED ERROR AND NOTHING ELSE — no key a console would render as data.
     *
     *  The status alone is not enough, and this assertion exists because a mutation proved it: an
     *  unwired-roster pin once passed against a mutation that collapsed the unwired case into a
     *  different 503 carrying the same sentence, because both were 503s. Asserting the ABSENCE of the
     *  payload keys is what makes these pins about the payload rather than about the status. */
    private fun assertErrorOnly(body: String) {
        val keys = json.parseToJsonElement(body).jsonObject.keys
        assertEquals(setOf("error"), keys, "an unwired or refused answer is the error and nothing else: $body")
    }

    private fun managedHead(): ManagedHead = head(
        key = HEAD_KEY,
        catalog = ModelCatalog(
            discoveryPrefix = "console--",
            models = listOf(ModelEntry(id = "m1", label = "M One", description = "d", contextWindow = 1_000L)),
            defaultContextWindow = 1_000L,
            pinnedModel = "m1",
        ),
        perfRows = PerfRowsSource { since -> PerfRowsWindow(rows.filter { it.ts >= since }, oldestHeldTs = 1_000L) },
    )

    /** Wireable on NO port — the fixture that proves each unwired branch is reachable at all. */
    private fun bareHead(): ManagedHead = head(key = "bare", catalog = null, perfRows = null)

    private fun head(key: String, catalog: ModelCatalog?, perfRows: PerfRowsSource?): ManagedHead = ManagedHead(
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
        catalog = catalog,
        perfRows = perfRows,
    )

    private suspend fun get(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.get("$url$path") { header("Authorization", "Bearer $key") }
    }

    /** The same requests with no bearer, so the guard is proven per route rather than once. */
    private suspend fun plain(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.get("$url$path")
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
