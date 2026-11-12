package splice.observability.export

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfEventKind
import splice.core.util.PerfWorkBudget
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ExportDrainTest {
    @Test
    fun `healthy collector receives fitting bursts and low volume before the next metric sample without flushing`() {
        val baseline = PerfWorkBudget.retainedBytes()
        DrainCollector().use { collector ->
            val snapshot = AtomicReference(ExportFixtures.snapshot())
            PerfExports(
                PerfExportConfig(
                    prometheus = true,
                    endpoint = collector.endpoint,
                    signals = setOf("metrics", "traces", "logs"),
                ),
                ExportFixtures.identity,
                PerfAggregateSource { snapshot.get() },
            ).use { exports ->
                // The initial tick already passed its drain when its metric payload reaches the collector.
                assertTrue(collector.metrics.await(3, TimeUnit.SECONDS))
                snapshot.set(ExportFixtures.snapshot(count = 7, until = 120_000))
                assertTimeoutPreemptively(Duration.ofSeconds(4)) {
                    repeat(100) { request(exports, it) }
                    awaitStatus(exports) { it.signal("logs", "exported") == 200L }
                    request(exports, 100)
                    awaitStatus(exports) { it.signal("logs", "exported") == 202L }
                    exports.scrape().use { scrape ->
                        assertTrue(scrape.available)
                        assertEquals(60_000L, scrape.sourceUntilMs, "event drains must not resample metrics")
                    }
                }
                ExportStatusRead(exports).use { status ->
                    assertEquals(202L, status.value.number("queue_enqueued"))
                    assertEquals(0L, status.value.number("queue_dropped"))
                    assertEquals(0L, status.value.number("queue_pending"))
                    assertEquals(101L, status.value.signal("traces", "exported"))
                    assertEquals(0L, status.value.signal("logs", "pending"))
                    assertEquals(0L, status.value.signal("logs", "failed"))
                }
                assertEquals(101, collector.logs.sumOf { occurrences(it, "REQUEST_FINISHED") })
                assertEquals(101, collector.traces.sumOf { occurrences(it, "splice.request") })
                assertTrue(collector.logs.any { occurrences(it, "REQUEST_FINISHED") > 1 }, "events remain batched")
            }
        }
        assertEquals(baseline, PerfWorkBudget.retainedBytes())
    }

    @Test
    fun `collector failure is visible and a later event recovers without blocking admission or leaking on close`() {
        val baseline = PerfWorkBudget.retainedBytes()
        DrainCollector().use { collector ->
            collector.healthy.set(false)
            val exports = PerfExports(
                PerfExportConfig(endpoint = collector.endpoint, signals = setOf("logs")),
                ExportFixtures.identity,
                PerfAggregateSource { ExportFixtures.snapshot() },
            )
            exports.use {
                assertTimeoutPreemptively(Duration.ofSeconds(1)) {
                    assertTrue(exports.record(ExportFixtures.event(PerfEventKind.REQUEST_FINISHED)))
                }
                assertTimeoutPreemptively(Duration.ofSeconds(4)) {
                    awaitStatus(exports) { it.signal("logs", "failed") == 1L }
                }
                collector.healthy.set(true)
                assertTrue(exports.record(ExportFixtures.event(PerfEventKind.REQUEST_FINISHED, 2_000)))
                assertTimeoutPreemptively(Duration.ofSeconds(4)) {
                    awaitStatus(exports) { it.signal("logs", "exported") == 1L }
                }
                ExportStatusRead(exports).use { status ->
                    assertEquals(2L, status.value.number("queue_enqueued"))
                    assertEquals(0L, status.value.number("queue_pending"))
                    assertEquals(0L, status.value.number("queue_dropped"))
                    assertEquals(1L, status.value.signal("logs", "dropped"))
                    assertEquals(1L, status.value.signal("logs", "failed"))
                    assertEquals(0L, status.value.signal("logs", "pending"))
                }
                assertTrue(collector.logs.any { it.contains("REQUEST_FINISHED") })
            }
            assertFalse(exports.record(ExportFixtures.event(PerfEventKind.REQUEST_FINISHED, 3_000)))
            ExportStatusRead(exports).use { status ->
                assertEquals("true", status.value.getValue("closed").jsonPrimitive.content)
                assertEquals(0L, status.value.number("queue_pending"))
            }
        }
        assertEquals(baseline, PerfWorkBudget.retainedBytes())
    }

    private fun request(exports: PerfExports, index: Int) {
        for (kind in listOf(PerfEventKind.REQUEST_ARRIVED, PerfEventKind.REQUEST_FINISHED)) {
            val event = ExportFixtures.event(kind, 1_000L + index * 100)
            val links = event.links.copy(
                requestId = "request-$index",
                spanId = (index + 1).toString(16).padStart(16, '0'),
            )
            assertTrue(exports.record(event.copy(links = links)))
        }
    }

    private fun awaitStatus(exports: PerfExports, ready: (JsonObject) -> Boolean) {
        while (true) {
            if (ExportStatusRead(exports).use { ready(it.value) }) return
            TimeUnit.MILLISECONDS.sleep(10)
        }
    }

    private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long
    private fun JsonObject.signal(signal: String, key: String): Long = getValue(signal).jsonObject.number(key)
    private fun occurrences(value: String, marker: String): Int = Regex(Regex.escape(marker)).findAll(value).count()
}

private class DrainCollector : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val metrics = CountDownLatch(1)
    val healthy = AtomicBoolean(true)
    val logs = ConcurrentLinkedQueue<String>()
    val traces = ConcurrentLinkedQueue<String>()
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/v1/") { exchange ->
            val payload = exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
            val success = healthy.get()
            if (success) {
                when (exchange.requestURI.path) {
                    "/v1/logs" -> logs.add(payload)
                    "/v1/traces" -> traces.add(payload)
                }
            }
            exchange.responseHeaders.set("Content-Type", "application/x-protobuf")
            exchange.sendResponseHeaders(if (success) 200 else 503, -1)
            exchange.close()
            if (exchange.requestURI.path == "/v1/metrics") metrics.countDown()
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
