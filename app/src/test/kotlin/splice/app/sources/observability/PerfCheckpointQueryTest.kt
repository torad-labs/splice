package splice.app.sources.observability

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.perf.PerfHttpWork
import splice.app.instrumentation.WorkBudgetMark
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfHistory
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfScope
import splice.core.util.AsyncFileIo
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.query.PerfQuery
import splice.usage.perf.query.PerfQueryService
import splice.usage.perf.query.PerfQuerySources
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PerfCheckpointQueryTest {
    @Test
    fun `HTTP source query waits for queued history work then returns the persisted population`(@TempDir dir: Path) {
        val budget = WorkBudgetMark()
        val identity = PerfIdentity("boot", "build", "1", "config")
        val clock = WallClock { 120_000L }
        val snapshot = PerfAggregateSnapshot(
            identity,
            60_000L,
            120_000L,
            listOf(
                PerfAggregatePoint(
                    "arrival_to_terminal_ms",
                    PerfScope(head = "one"),
                    60_000L,
                    120_000L,
                    110,
                    1_100.0,
                    10.0,
                    10.0,
                ),
            ),
            PerfCoverage(60_000L, 120_000L, 60_000L),
        )
        // Ordinary long-lived owners plus the unchanged 8 MiB writer leave less than a 4 MiB query reply.
        checkNotNull(PerfWorkBudget.acquire(11L * 1_024 * 1_024 / 2)).use {
            PerfAggregateStore(dir, identity, clock).use { store ->
                queuedSnapshot(store, snapshot, dir)
            }
        }
        budget.assertGivenBack()
    }

    private fun queuedSnapshot(store: PerfAggregateStore, snapshot: PerfAggregateSnapshot, dir: Path) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        PerfQueryService(
            object : PerfQuerySources {
                override fun heads(): Set<String> = setOf("one")
                override fun rows(head: String): PerfRowsSource? = null
                override fun history(): PerfHistory = store
            },
            clock = WallClock { 120_000L },
        ).use { service ->
            assertTrue(AsyncFileIo.submit { entered.countDown(); release.await(10, TimeUnit.SECONDS) })
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(store.append(snapshot))
                assertEquals(1L, store.status().pending)
                assertTrue(PerfWorkBudget.LIMIT_BYTES - PerfWorkBudget.retainedBytes() < 4L * 1_024 * 1_024)
                queryWhileQueued(service, release)
                assertTrue(AsyncFileIo.awaitFile(dir.resolve("checkpoint.json")))
                assertEquals(1L, store.status().written)
                assertEquals(0L, store.status().failed + store.status().dropped)
            } finally {
                release.countDown()
            }
        }
    }

    private fun queryWhileQueued(service: PerfQueryService, release: CountDownLatch) = testApplication {
        val work = PerfHttpWork()
        val requested = CompletableDeferred<Unit>()
        application {
            routing {
                get("/api/perf/series") {
                    requested.complete(Unit)
                    work.source(call) {
                        work.respond(call, null) { service.series(PerfQuery(60_000L, 120_000L, source = "history")) }
                    }
                }
            }
        }
        coroutineScope {
            val response = async { client.get("/api/perf/series") }
            try {
                withTimeout(5_000L) { requested.await() }
                assertNull(withTimeoutOrNull(200L) { response.await() }, "queued history still owns the work phase")
                release.countDown()
                val actual = withTimeout(5_000L) { response.await() }
                assertEquals(HttpStatusCode.OK, actual.status)
                val body = actual.bodyAsText()
                assertFalse(body.contains("memory_busy"), body)
                assertFalse(body.contains("checkpoint_dropped"), body)
                val series = Json.parseToJsonElement(body).jsonObject.getValue("series").jsonArray
                val count = series.sumOf { row ->
                    row.jsonObject.getValue("metrics").jsonObject.values.sumOf { metric ->
                        checkNotNull(JsonScalars.str(metric.jsonObject, "count")).toLong()
                    }
                }
                assertEquals(110L, count)
            } finally {
                release.countDown()
            }
        }
    }
}
