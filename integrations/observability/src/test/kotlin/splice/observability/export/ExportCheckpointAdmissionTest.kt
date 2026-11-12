package splice.observability.export

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.util.PerfWorkPhase
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ExportCheckpointAdmissionTest {
    @Test
    fun `final export reports incomplete while checkpoint owns phase`() {
        val requests = ConcurrentLinkedQueue<String>()
        val collector = collector(requests)
        collector.start()
        val checkpoint = requireNotNull(PerfWorkPhase.tryCheckpoint())
        val exports = PerfExports(
            PerfExportConfig(
                endpoint = "http://127.0.0.1:${collector.address.port}",
                signals = setOf("metrics"),
            ),
            ExportFixtures.identity,
            PerfAggregateSource { ExportFixtures.snapshot() },
        )
        try {
            assertFalse(exports.closeAndFlushWithin(250))
            assertTrue(requests.isEmpty())
            checkpoint.close()
            awaitFinalized(exports)
            assertTrue(requests.isEmpty())
            ExportStatusRead(exports).use { status ->
                assertTrue(status.value.getValue("closed").jsonPrimitive.boolean)
                assertTrue(status.value.getValue("worker_finalized").jsonPrimitive.boolean)
            }
        } finally {
            exports.close()
            checkpoint.close()
            collector.stop(0)
        }
    }

    @Test
    fun `expired finalizer stays queued behind active export and skips another source sample`() {
        val sampleStarted = CountDownLatch(1)
        val releaseSample = CountDownLatch(1)
        val requests = ConcurrentLinkedQueue<String>()
        val collector = collector(requests)
        collector.start()
        val sourceSnapshot = AtomicReference(ExportFixtures.snapshot(count = 3))
        val firstSample = AtomicBoolean()
        val exports = PerfExports(
            PerfExportConfig(
                endpoint = "http://127.0.0.1:${collector.address.port}",
                signals = setOf("metrics"),
            ),
            ExportFixtures.identity,
            PerfAggregateSource {
                val selected = sourceSnapshot.get()
                if (firstSample.compareAndSet(false, true)) {
                    sampleStarted.countDown()
                    releaseSample.await(5, TimeUnit.SECONDS)
                }
                selected
            },
        )
        try {
            assertTrue(sampleStarted.await(1, TimeUnit.SECONDS))
            assertFalse(exports.closeAndFlushWithin(0))
            ExportStatusRead(exports).use { status ->
                assertFalse(status.value.getValue("worker_finalized").jsonPrimitive.boolean)
            }
            sourceSnapshot.set(ExportFixtures.snapshot(count = 9))
            releaseSample.countDown()
            awaitFinalized(exports)
            assertEquals(listOf("/v1/metrics"), requests.toList())
        } finally {
            releaseSample.countDown()
            exports.close()
            collector.stop(0)
        }
    }

    private fun collector(requests: MutableCollection<String>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                requests += exchange.requestURI.path
                exchange.responseHeaders.set("Content-Type", "application/x-protobuf")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }

    private fun awaitFinalized(exports: PerfExports) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        val finalized = awaitUntil(deadline) {
            ExportStatusRead(exports).use { status ->
                status.value.getValue("worker_finalized").jsonPrimitive.boolean
            }
        }
        assertTrue(
            finalized,
            "worker_finalized did not become true before the deadline",
        )
    }

    private inline fun awaitUntil(deadlineNanos: Long, condition: () -> Boolean): Boolean {
        while (true) {
            if (System.nanoTime() >= deadlineNanos) return false
            if (condition()) return System.nanoTime() < deadlineNanos
            Thread.yield()
        }
    }
}
