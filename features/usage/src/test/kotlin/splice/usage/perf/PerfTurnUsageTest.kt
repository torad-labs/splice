// NEW: V4-444 — route controls prove full-window usage beyond the display limit.
package splice.usage.perf

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.perf.PerfKeys
import splice.core.usage.QuotaView
import splice.core.util.WallClock
import splice.usage.UsageBilling
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.time.Instant

class PerfTurnUsageTest {
    private val fields = mapOf(
        PerfKeys.IN_TOKENS to 1_000L,
        PerfKeys.CACHED_TOKENS to 900L,
        PerfKeys.OUT_TOKENS to 100L,
    )
    private val priced = (0..2_500).map { i ->
        PerfRow(
            ts = 1_000L + i,
            outcome = "ok",
            fields = fields,
            model = if (i == 0) "earlier" else "m",
            account = if (i == 0) "earlier-account" else "work",
            sessionId = "synthetic-full-session",
        )
    }
    private val failure = PerfRow(
        ts = 3_600L,
        outcome = "quota-refused",
        fields = emptyMap(),
        model = "m",
        account = "spare",
        sessionId = "synthetic-full-session",
    )
    private val local = priced.first().copy(
        ts = 2_000L,
        fields = fields + (PerfKeys.LOCAL_STEP to 1L),
    )
    private val outside = listOf(priced.first().copy(ts = 999), priced.last().copy(ts = 4_000))

    private fun ApplicationTestBuilder.mount(
        rows: List<PerfRow>,
        usage: HeadUsageSource = HeadUsageSource { UsageView(0, 0, null) },
        accountPool: HeadAccountPoolSource? = null,
        onRead: () -> Unit = {},
        billing: UsageBilling? = null,
    ) {
        val catalog = ModelCatalog(
            discoveryPrefix = "synthetic--",
            models = listOf("m", "earlier").map { id ->
                ModelEntry(id, contextWindow = 100_000, rates = ModelRates(1.0, 0.1, 4.0))
            } + ModelEntry("free", contextWindow = 100_000),
            defaultContextWindow = 100_000,
        )
        val source = PerfRowsSource { since ->
            onRead()
            PerfRowsWindow(rows.filter { it.ts >= since })
        }
        val head = UsageHead(
            key = "synthetic",
            label = "Synthetic",
            usage = usage,
            warnPct = 80,
            warnTokens5h = 0,
            perfRows = source,
            catalog = catalog,
            accountPool = accountPool,
        )
        val lookup = object : UsageHeadLookup {
            override fun byName(name: String): List<UsageHead> = listOf(head)
            override fun billing(key: String): UsageBilling? = billing
        }
        val routes = PerfRoutes(lookup, WallClock { 5_000 })
        application { routing { get("/api/perf/turns") { routes.turns(call) } } }
    }

    private fun head(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
        .getValue("heads").jsonArray.single().jsonObject

    @Test
    fun `whole-window usage precedes the row limit and excludes local steps and interval edges`() = testApplication {
        var reads = 0
        mount(priced + failure + local + outside, onRead = { reads++ })
        val response = client.get(
            "/api/perf/turns?head=synthetic&since=1000&until=4000&n=2000&local=0&time_zone=UTC",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val block = head(response.bodyAsText())
        assertEquals(1, reads)
        assertEquals(2_000, block.getValue("rows").jsonArray.size)
        assertEquals(2_502L, block.getValue("count").jsonPrimitive.long)
        val usage = block.getValue("usage").jsonObject
        val totals = usage.getValue("totals").jsonObject
        assertEquals(2_502L, totals.getValue("requests").jsonPrimitive.long)
        assertEquals(2_501_000L, totals.getValue("input_tokens").jsonPrimitive.long)
        assertEquals(2_250_900L, totals.getValue("cached_tokens").jsonPrimitive.long)
        assertEquals(250_100L, totals.getValue("output_tokens").jsonPrimitive.long)
        assertEquals(0.9, totals.getValue("cache_share").jsonPrimitive.double, 0.000001)
        assertEquals(1.47559, totals.getValue("cost_usd").jsonPrimitive.double, 0.000001)
        assertEquals(0L, totals.getValue("unpriced_requests").jsonPrimitive.long)
        assertEquals(1L, totals.getValue("unanswered_requests").jsonPrimitive.long)
        val models = usage.getValue("models").jsonArray.map { it.jsonObject }
        val earlier = models.single { it.getValue("key").jsonPrimitive.content == "earlier" }
        assertEquals(1L, earlier.getValue("requests").jsonPrimitive.long)
        val accounts = usage.getValue("accounts").jsonArray.map { it.jsonObject }
        assertTrue(accounts.any { it.getValue("key").jsonPrimitive.content == "earlier-account" })
        val day = usage.getValue("days").jsonArray.single().jsonObject
        assertEquals(2_502L, day.getValue("requests").jsonPrimitive.long)
        val session = usage.getValue("sessions").jsonArray.single().jsonObject
        assertEquals("synthetic-full-session", session.getValue("key").jsonPrimitive.content)
        assertEquals(2_502L, session.getValue("requests").jsonPrimitive.long)
    }

    /** Marlin's pass 5: one sentence, "no recorded price", stood for three causes. Each unpriced request is
     *  counted under the cause the head can name: a priced model whose request has no token count, a model with
     *  no price on an account a plan covers, and a model whose price was never declared. */
    @Test
    fun `an unpriced request is counted under its own cause`() = testApplication {
        val pool = HeadAccountPoolSource {
            HeadAccountPoolView(
                "plan",
                listOf(
                    HeadAccountView("plan", true, true, true, "pro", null, null, null, null),
                    HeadAccountView("key", false, false, true, null, null, null, null, null),
                ),
                null,
            )
        }
        val row = priced.last()
        mount(
            listOf(
                row,
                row.copy(ts = 1_001, fields = emptyMap(), account = "key"),
                row.copy(ts = 1_002, fields = emptyMap(), account = "plan"),
                row.copy(ts = 1_003, model = "free", account = "plan"),
                row.copy(ts = 1_004, model = "free", account = "key"),
            ),
            accountPool = pool,
        )
        val usage = head(client.get("/api/perf/turns?head=synthetic&since=1000&local=0").bodyAsText())
            .getValue("usage").jsonObject
        val totals = usage.getValue("totals").jsonObject
        assertEquals(listOf(4L, 2L, 1L, 1L), causes(totals))
        val models = usage.getValue("models").jsonArray.associate { it.jsonObject.let { m -> m.key() to causes(m) } }
        assertEquals(listOf(2L, 2L, 0L, 0L), models["m"])
        assertEquals(listOf(2L, 0L, 1L, 1L), models["free"])
        assertSummed(usage)
    }

    @Test
    fun `a head with no account pool is covered by the plan its own quota names`() = testApplication {
        val plan = HeadUsageSource { UsageView(0, 0, null, QuotaView(null, null, "max")) }
        mount(listOf(priced.last().copy(model = "free", account = "primary")), usage = plan)
        val usage = head(client.get("/api/perf/turns?head=synthetic&since=1000&local=0").bodyAsText())
            .getValue("usage").jsonObject
        assertEquals(listOf(1L, 0L, 1L, 0L), causes(usage.getValue("totals").jsonObject))
        assertSummed(usage)
    }

    @Test
    fun `subscription and local heads classify unpriced rows without a quota plan or account record`() {
        for ((kind, cause) in listOf(
            UsageBilling.SUBSCRIPTION to PLAN,
            UsageBilling.LOCAL_RUNTIME to "unpriced_local_requests",
        )) {
            testApplication {
                mount(
                    listOf(priced.last().copy(model = "free"), priced.last().copy(ts = 1_002, fields = emptyMap())),
                    usage = HeadUsageSource { error("billing kind must not need quota metadata") },
                    billing = kind,
                )
                val usage = head(client.get("/api/perf/turns?head=synthetic&since=1000&local=0").bodyAsText())
                    .getValue("usage").jsonObject
                val groups = listOf(usage.getValue("totals").jsonObject) +
                    listOf("models", "accounts", "days", "sessions").flatMap { name ->
                        usage.getValue(name).jsonArray.map { it.jsonObject }
                    }
                groups.forEach { group ->
                    assertEquals(group.getValue(UNPRICED), group.getValue(cause), "$kind: $group")
                    assertEquals(0L, group.getValue(UNCOUNTED).jsonPrimitive.long, "$kind: $group")
                    assertEquals(0L, group.getValue(UNDECLARED).jsonPrimitive.long, "$kind: $group")
                }
            }
        }
    }

    @Test
    fun `failed unanswered rows are separate from spend gaps but partial usage and cut rounds stay visible`() =
        testApplication {
            val partial = listOf(
                mapOf(PerfKeys.IN_TOKENS to 3L),
                mapOf(PerfKeys.OUT_TOKENS to 3L),
                mapOf(PerfKeys.FIRST_DELTA to 100L),
                mapOf(PerfKeys.CONTENT_FRAMES_OUT to 1L),
                mapOf(PerfKeys.CUT_SOURCE_ROUNDS to 1L),
                mapOf(PerfKeys.ABSORBED_ROUNDS to 1L),
                mapOf(PerfKeys.UPSTREAM_REQ_BYTES to 32L),
                mapOf(PerfKeys.UPSTREAM_REQ_BYTES to 32L, PerfKeys.ATTEMPTS to 0L, PerfKeys.CONTENT_FRAMES_OUT to 1L),
            ).mapIndexed { index, evidence -> failure.copy(ts = 1_010L + index, fields = evidence) }
            mount(
                listOf(failure, failure.copy(ts = 1_001, model = "free")) +
                    partial + priced.last().copy(ts = 1_030, fields = emptyMap()),
            )
            val usage = head(client.get("/api/perf/turns?head=synthetic&since=1000&local=0").bodyAsText())
                .getValue("usage").jsonObject
            val totals = usage.getValue("totals").jsonObject
            assertEquals(2L, totals.getValue("unanswered_requests").jsonPrimitive.long)
            assertEquals(9L, totals.getValue(UNPRICED).jsonPrimitive.long)
            assertEquals(9L, totals.getValue(UNCOUNTED).jsonPrimitive.long)
            assertEquals(0L, totals.getValue(UNDECLARED).jsonPrimitive.long)
            assertEquals(JsonNull, totals.getValue("cost_usd"))
            assertEquals(1L, totals.getValue(CUT).jsonPrimitive.long)
            assertEquals(3L, totals.getValue("input_tokens").jsonPrimitive.long)
            assertEquals(3L, totals.getValue("output_tokens").jsonPrimitive.long)
            listOf("models", "accounts", "days", "sessions").forEach { name ->
                val groups = usage.getValue(name).jsonArray.map { it.jsonObject }
                assertEquals(2L, groups.sumOf { it.getValue("unanswered_requests").jsonPrimitive.long }, name)
                assertEquals(9L, groups.sumOf { it.getValue(UNPRICED).jsonPrimitive.long }, name)
            }
        }

    /** A source round a turn cut was billed upstream and never reported, so its row carries only the count of it.
     *  Totals and every group sum the count, and a row without the key counts none. */
    @Test
    fun `cut source rounds are summed in totals and every group`() = testApplication {
        val row = priced.last()
        val twice = fields + (PerfKeys.CUT_SOURCE_ROUNDS to 2L)
        mount(
            listOf(
                row,
                row.copy(ts = 1_001, fields = twice, model = "earlier", account = "spare"),
                row.copy(ts = 1_002, fields = fields + (PerfKeys.CUT_SOURCE_ROUNDS to 1L), sessionId = "other-session"),
            ),
        )
        val usage = head(client.get("/api/perf/turns?head=synthetic&since=1000&local=0&time_zone=UTC").bodyAsText())
            .getValue("usage").jsonObject
        assertEquals(3L, usage.getValue("totals").jsonObject.cut())
        assertEquals(mapOf("m" to 1L, "earlier" to 2L), cutBy(usage, "models"))
        assertEquals(mapOf("work" to 1L, "spare" to 2L), cutBy(usage, "accounts"))
        assertEquals(mapOf("synthetic-full-session" to 2L, "other-session" to 1L), cutBy(usage, "sessions"))
        assertEquals(listOf(3L), usage.getValue("days").jsonArray.map { it.jsonObject.cut() })
    }

    private fun JsonObject.cut(): Long? = this[CUT]?.jsonPrimitive?.long

    private fun cutBy(usage: JsonObject, name: String): Map<String, Long?> =
        usage.getValue(name).jsonArray.associate { it.jsonObject.let { group -> group.key() to group.cut() } }

    /** The unpriced total, then its causes: no token count, covered by a plan, no declared price. */
    private fun causes(group: JsonObject): List<Long> =
        listOf(UNPRICED, UNCOUNTED, PLAN, UNDECLARED).map { group.getValue(it).jsonPrimitive.long }

    private fun JsonObject.key(): String = getValue("key").jsonPrimitive.content

    /** Every group's causes sum to its unpriced_requests, the total every existing reader keeps reading. */
    private fun assertSummed(usage: JsonObject) {
        val groups = listOf(usage.getValue("totals").jsonObject) +
            listOf("models", "accounts", "days", "sessions").flatMap { name ->
                usage.getValue(name).jsonArray.map { it.jsonObject }
            }
        groups.forEach { group ->
            val counts = causes(group)
            assertEquals(counts.first(), counts.drop(1).sum(), group.toString())
        }
    }

    @Test
    fun `the same outcome filter controls both the list count and its aggregate`() = testApplication {
        mount(priced + failure + local)
        val response = client.get(
            "/api/perf/turns?head=synthetic&since=1000&until=4000&n=1&local=0&outcome=failed",
        )
        val block = head(response.bodyAsText())
        assertEquals(1L, block.getValue("count").jsonPrimitive.long)
        val totals = block.getValue("usage").jsonObject.getValue("totals").jsonObject
        assertEquals(1L, totals.getValue("requests").jsonPrimitive.long)
        assertEquals(JsonNull, totals["input_tokens"])
        assertEquals(JsonNull, totals["cost_usd"])
    }

    @Test
    fun `session models keep the latest ordinary request rather than a compactor or short tag`() = testApplication {
        val ordinary = priced.first().copy(ts = 1_500, model = "earlier")
        val compact = priced.last().copy(ts = 2_000, compact = true, model = "m")
        val legacy = priced.first().copy(sessionId = null, session = "syntheti")
        mount(listOf(ordinary, compact, legacy))
        val response = client.get("/api/perf/turns?head=synthetic&since=1000&n=1&local=0")
        val sessions = head(response.bodyAsText()).getValue("usage").jsonObject.getValue("sessions").jsonArray
        val full = sessions.map { it.jsonObject }.single { it.getValue("key") != JsonNull }
        assertEquals(2L, full.getValue("requests").jsonPrimitive.long)
        assertEquals("earlier", full.getValue("last_model").jsonPrimitive.content)
        assertEquals(1_500L, full.getValue("last_model_ts_epoch_ms").jsonPrimitive.long)
        val unassigned = sessions.map { it.jsonObject }.single { it.getValue("key") == JsonNull }
        assertEquals(1L, unassigned.getValue("requests").jsonPrimitive.long)
    }

    @Test
    fun `day groups use the requested viewer zone and a misspelled zone is refused`() = testApplication {
        val first = Instant.parse("2026-10-03T06:59:00Z").toEpochMilli()
        val second = Instant.parse("2026-10-03T07:01:00Z").toEpochMilli()
        mount(listOf(priced.first().copy(ts = first), priced.last().copy(ts = second)))
        val response = client.get(
            "/api/perf/turns?head=synthetic&since=0&time_zone=America%2FLos_Angeles&local=0",
        )
        val days = head(response.bodyAsText()).getValue("usage").jsonObject.getValue("days").jsonArray
        assertEquals(
            listOf("2026-10-02", "2026-10-03"),
            days.map { it.jsonObject.getValue("key").jsonPrimitive.content },
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/perf/turns?head=synthetic&since=0&time_zone=wrong-zone").status,
        )
    }
}

private const val UNPRICED = "unpriced_requests"
private const val UNCOUNTED = "unpriced_uncounted_requests"
private const val PLAN = "unpriced_plan_requests"
private const val UNDECLARED = "unpriced_undeclared_requests"
private const val CUT = "cut_source_rounds"
