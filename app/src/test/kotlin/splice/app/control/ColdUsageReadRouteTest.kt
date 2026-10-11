package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.mount.UsageReadWarmup
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.perf.PerfKeys
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.economics.EconomicsBytes
import splice.usage.economics.EconomicsCost
import splice.usage.economics.EconomicsRead
import splice.usage.economics.EconomicsRow
import splice.usage.economics.EconomicsTokens
import splice.usage.economics.EconomicsTools
import splice.usage.economics.EconomicsTurnCounts
import splice.usage.economics.HeadEconomicsSource
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.PerfTurnFacts
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val PENDING_HEADER = "x-splice-read-pending"

class ColdUsageReadRouteTest {
    @Test
    fun `a cold hourly read answers pending before its scan can finish`(@TempDir dir: Path) = runBlocking<Unit> {
        val fixture = Fixture(dir)
        fixture.withServer {
            val request = async { fixture.get("/api/economics", pending = true) }
            fixture.entered.await()
            val first = withTimeoutOrNull(30_000) { request.await() }
            fixture.release.countDown()
            request.await()
            assertNotNull(first, "the real first response must arrive while the cold scan is still held")
            val loading = first!!.head()
            assertEquals(true, loading["read_pending"]?.jsonPrimitive?.booleanOrNull)
            assertTrue(loading.getValue("buckets").jsonArray.isEmpty())
            val finished = fixture.untilReady("/api/economics")
            val legacy = fixture.get("/api/economics", pending = false)
            assertEquals(legacy.head(), finished.head(), "the same synchronous reader owns the final hourly totals")
            val bucket = finished.head().getValue("buckets").jsonArray.single().jsonObject
            assertEquals(5L, bucket.getValue("turns").jsonPrimitive.content.toLong())
        }
    }

    @Test
    fun `a cold request window answers pending and retains its complete totals after release`(@TempDir dir: Path) =
        runBlocking<Unit> {
            val fixture = Fixture(dir)
            fixture.withServer {
                val path = fixture.requestsPath()
                val request = async { fixture.get(path, pending = true) }
                fixture.entered.await()
                val first = withTimeoutOrNull(30_000) { request.await() }
                fixture.release.countDown()
                request.await()
                assertNotNull(first, "request-window reads must not wait on the cold source monitor")
                assertEquals(true, first!!.head()["read_pending"]?.jsonPrimitive?.booleanOrNull)
                assertTrue(!first.head().containsKey("count"), "unknown is not an invented zero request count")
                assertTrue(!first.head().containsKey("usage"), "no partial usage is presented as a complete total")
                val finished = fixture.untilReady(path)
                assertEquals(fixture.get(path, pending = false), finished)
                assertEquals(2L, finished.head().getValue("count").jsonPrimitive.content.toLong())
            }
        }

    @Test
    fun `daemon startup begins preparation without an economics or request route call`(@TempDir dir: Path) =
        runBlocking<Unit> {
            val fixture = Fixture(dir)
            fixture.withServer {
                val began = withTimeoutOrNull(30_000) {
                    fixture.entered.await()
                    true
                }
                fixture.release.countDown()
                assertEquals(true, began, "startup, not the first Usage route, owns the preparation trigger")
                fixture.untilReady("/api/economics")
            }
        }

    @Test
    fun `a legacy console waits for its original complete answer instead of receiving a pending body`(
        @TempDir dir: Path,
    ) =
        runBlocking<Unit> {
            val fixture = Fixture(dir)
            fixture.withServer {
                val request = async { fixture.get("/api/economics", pending = false) }
                fixture.entered.await()
                val early = withTimeoutOrNull(100) { request.await() }
                fixture.release.countDown()
                val complete = request.await()
                assertEquals(null, early)
                assertTrue(!complete.head().containsKey("read_pending"))
                val bucket = complete.head().getValue("buckets").jsonArray.single().jsonObject
                assertEquals(5L, bucket.getValue("turns").jsonPrimitive.content.toLong())
            }
        }

    @Test
    fun `cancelling preparation during economics prevents the next request scan`(@TempDir dir: Path) =
        runBlocking<Unit> {
            val fixture = Fixture(dir)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                UsageReadWarmup(
                    scope,
                    UsageHeadAdapter.heads(mapOf("synthetic" to fixture.managed())),
                    Dispatchers.IO,
                ).start()
                withTimeout(5_000) { fixture.entered.await() }
                scope.cancel()
                fixture.release.countDown()
                withTimeout(5_000) { scope.coroutineContext.job.join() }
                assertEquals(
                    0,
                    fixture.perfScans.get(),
                    "a cancelled owner may finish its current read, never start another",
                )
            } finally {
                scope.cancel()
                fixture.release.countDown()
                fixture.closeUnstarted()
            }
        }

    private class Fixture(dir: Path) {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val perfScans = AtomicInteger()
        private val now = System.currentTimeMillis()
        private val paths = StatePaths(baseOverride = dir)
        private val mgmt = MgmtKey(paths)
        private val key = mgmt.get()
        private val client = HttpClient(CIO)
        private val server = controlServerFor(
            0,
            mapOf("synthetic" to managed()),
            ConfigService(paths),
            ControlAuth(mgmt, {}),
        )

        suspend fun <T> withServer(action: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
            server.start()
            return try {
                withTimeout(15_000) { coroutineScope(action) }
            } finally {
                release.countDown()
                server.stop()
                client.close()
            }
        }

        suspend fun get(path: String, pending: Boolean): JsonObject {
            val response = client.get("http://127.0.0.1:${server.listeningPort}$path") {
                header("Authorization", "Bearer $key")
                if (pending) header(PENDING_HEADER, "1")
            }
            assertEquals(200, response.status.value)
            return Json.parseToJsonElement(response.bodyAsText()).jsonObject
        }

        suspend fun untilReady(path: String): JsonObject = withTimeout(5_000) {
            var response = get(path, pending = true)
            while (response.head()["read_pending"]?.jsonPrimitive?.booleanOrNull == true) {
                kotlinx.coroutines.yield()
                response = get(path, pending = true)
            }
            response
        }

        fun requestsPath(): String =
            "/api/perf/turns?head=synthetic&n=1&since=${now - 60_000}&until=${now + 1}&local=0"

        fun closeUnstarted() { client.close() }

        fun managed(): ManagedHead = ManagedHead(
            head = object : Head {
                override val key = "synthetic"
                override val label = "Synthetic"
                override val port = 0
                override suspend fun start() = Unit
                override suspend fun stop() = Unit
                override fun healthSnapshot() = HeadHealth(true, true, 0, "synthetic")
            },
            auth = object : AuthProvider {
                override suspend fun credentials() = null
                override suspend fun describe() = AuthDescription(true, "synthetic", emptyMap())
            },
            sources = HeadSources(
                usage = HeadUsageSource { UsageView(0, 0, null) },
                compact = object : HeadCompactSource {
                    override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
                },
                logs = object : HeadLogSource {
                    override fun tail(lines: Int) = ""
                    override fun path() = ""
                },
                economics = economicsSource(),
                perfRows = perfSource(),
            ),
            usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
        )

        private fun economicsSource(): HeadEconomicsSource = HeadEconomicsSource {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "the synthetic scan was not released" }
            EconomicsRead.Rows(
                listOf(
                    EconomicsRow(
                        hour = now / 3_600_000 * 3_600_000,
                        counts = EconomicsTurnCounts(turns = 5),
                        tokens = EconomicsTokens(
                            inTokens = 100,
                            cachedTokens = 30,
                            cacheWriteTokens = 10,
                            outTokens = 7,
                        ),
                        bytes = EconomicsBytes(reqBytes = 20, upstreamBytes = 18),
                        tools = EconomicsTools(toolsEager = 3, toolsDeferred = 1, deferralTurns = 1),
                        rateLimited = 0,
                        cost = EconomicsCost(costUsd = 1.25, unpricedTurns = 0),
                    ),
                ),
            )
        }

        private fun perfSource(): PerfRowsSource = PerfRowsSource {
            perfScans.incrementAndGet()
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "the synthetic scan was not released" }
            PerfRowsWindow(
                listOf(1L, 2L).map { index ->
                    PerfRow(
                        ts = now - index,
                        outcome = "ok",
                        fields = mapOf(
                            PerfKeys.IN_TOKENS to 100,
                            PerfKeys.OUT_TOKENS to 7,
                            PerfKeys.CACHED_TOKENS to 30,
                            PerfKeys.CACHE_WRITE_TOKENS to 10,
                        ),
                        facts = PerfTurnFacts(model = "synthetic", account = "synthetic", compact = false),
                    )
                },
            )
        }
    }
}

private fun JsonObject.head(): JsonObject = getValue("heads").jsonArray.single().jsonObject
