// NEW: V4-221 — /api/economics carries each hour's dollars, every turn priced at its own model's card.
// The console priced a head's whole hour at its PINNED card, so a haiku subagent turn on a fable head
// read fifteen times its cost. Driven end to end: a real EconomicsStore priced by the head's catalog,
// the control plane's row adapter, and the route the console reads, under the bearer.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.app.sources.EconomicsStoreSource
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.model.TurnPrice
import splice.core.util.WallClock
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.usage.EconomicsStore
import splice.head.usage.TurnEconomics
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val HOUR_MS = 3_600_000L
private const val FABLE = "claude-fable-5"
private const val HAIKU = "claude-haiku-4-5"
private val FABLE_RATES = ModelRates(input = 15.0, cacheRead = 1.5, output = 75.0, cacheWrite = 18.75)
private val HAIKU_RATES = ModelRates(input = 1.0, cacheRead = 0.1, output = 5.0, cacheWrite = 1.25)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomicsCostRouteTest {

    private val port: Int get() = control.listeningPort
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var control: ControlServer
    private lateinit var key: String
    private lateinit var tmp: Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-splice--",
        models = listOf(
            ModelEntry(FABLE, contextWindow = 200_000, rates = FABLE_RATES),
            ModelEntry(HAIKU, contextWindow = 200_000, rates = HAIKU_RATES),
        ),
        defaultContextWindow = 200_000,
        pinnedModel = FABLE,
    )

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("economics-cost")
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = 0,
            heads = mapOf(
                "priced" to managedHead("priced", pricedStore()),
                "legacy" to managedHead("legacy", legacyStore()),
            ),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    /** A fable head that ran one haiku turn, one fable turn and one turn on a model with no card. */
    private fun pricedStore(): EconomicsStore {
        val store = EconomicsStore(tmp.resolve("priced.json"), TurnPrice(catalog), WallClock { 10 * HOUR_MS })
        store.record(turn(HAIKU, inTokens = 100_000, cached = 60_000, cacheWrite = 10_000, out = 2_000))
        store.record(turn(FABLE, inTokens = 1_000, cached = 0, cacheWrite = 0, out = 100))
        store.record(turn("gpt-5.6-sol", inTokens = 1_000, cached = 0, cacheWrite = 0, out = 100))
        return store
    }

    /** An hour written before the cost field existed. */
    private fun legacyStore(): EconomicsStore {
        val file = tmp.resolve("legacy.json")
        Files.writeString(
            file,
            """[{"hour":36000000,"turns":3,"in_tokens":1000,"cached_tokens":900,"cache_write_tokens":0,""" +
                """"out_tokens":40,"req_bytes":0,"upstream_req_bytes":0,"tools_eager":0,"tools_deferred":0,""" +
                """"deferral_turns":0,"rate_limited":0}]""" + "\n",
        )
        return EconomicsStore(file, TurnPrice(catalog), WallClock { 10 * HOUR_MS })
    }

    /** RED before V4-221: the bucket carried token sums only, and the console priced them at fable's card. */
    @Test
    fun `each turn is priced at its own model's card, and a card-less turn is counted, not zeroed`() = runBlocking<Unit> {
        awaitPort()
        val bucket = bucketOf("priced")

        val cost = TokenCost()
        val haikuTokens = TokenBuckets(input = 30_000, cacheRead = 60_000, cacheWrite = 10_000, output = 2_000)
        val haiku = cost.of(haikuTokens, HAIKU_RATES)
        val fable = cost.of(TokenBuckets(input = 1_000, output = 100), FABLE_RATES)
        assertEquals(haiku + fable, bucket.getValue("cost_usd").jsonPrimitive.double, 1e-9, "$bucket")
        assertEquals(1L, bucket.getValue("unpriced_turns").jsonPrimitive.long, "$bucket")
    }

    @Test
    fun `an hour recorded before the daemon priced turns reads null, never zero`() = runBlocking<Unit> {
        awaitPort()
        assertEquals(JsonNull, bucketOf("legacy").getValue("cost_usd"))
    }

    private suspend fun bucketOf(head: String): JsonObject {
        val body = withTimeout(TIMEOUT_MS) {
            client.get("http://127.0.0.1:$port/api/economics") { header("Authorization", "Bearer $key") }.bodyAsText()
        }
        val row = json.parseToJsonElement(body).jsonObject.getValue("heads").jsonArray
            .map { it.jsonObject }.single { it["key"]?.jsonPrimitive?.content == head }
        return row.getValue("buckets").jsonArray.single().jsonObject
    }

    private fun turn(model: String, inTokens: Long, cached: Long, cacheWrite: Long, out: Long) =
        TurnEconomics(model, inTokens, cached, cacheWrite, out, null, null, null, null)

    private fun managedHead(name: String, store: EconomicsStore): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = name
            override val label: String = name
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(true, "test", emptyMap())
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
        economics = EconomicsStoreSource(store),
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
