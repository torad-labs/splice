package splice.observability.export

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfEventKind
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExportSlowCollectorTest {
    @Test
    fun `slow collector times out while record admission remains available`() {
        val release = CountDownLatch(1)
        val collector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        collector.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        collector.start()
        try {
            val config =
                PerfExportConfig(endpoint = "http://127.0.0.1:${collector.address.port}", signals = setOf("metrics"))
            PerfExports(
                config,
                ExportFixtures.identity,
                PerfAggregateSource { ExportFixtures.snapshot() },
            ).use { exports ->
                assertTimeoutPreemptively(Duration.ofSeconds(1)) {
                    assertTrue(exports.record(ExportFixtures.event(PerfEventKind.REQUEST_ARRIVED)))
                }
                assertTimeoutPreemptively(Duration.ofSeconds(10)) { assertFalse(exports.flush()) }
                assertTrue(exports.status().getValue("metrics").jsonObject.getValue("failed").jsonPrimitive.long > 0)
                release.countDown()
            }
        } finally {
            release.countDown()
            collector.stop(0)
        }
    }
}
