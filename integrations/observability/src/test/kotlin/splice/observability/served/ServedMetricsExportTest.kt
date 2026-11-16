// The server's per-person metrics reach the operator's OTLP collector (docs/specs/server-mode.md, F6): each family
// under its own name, its series carrying the person and team, and nothing is sent where `[telemetry.operator]` sends
// no metrics.
package splice.observability.served

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.server.HistogramPoint
import splice.core.server.MetricFamily
import splice.core.server.MetricLabels
import splice.core.server.MetricPoint
import splice.core.server.MetricSnapshot
import splice.core.server.ServedMetricSource
import splice.core.util.LogSink
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

class ServedMetricsExportTest {
    private val received = ConcurrentLinkedQueue<Pair<String, ByteArray>>()

    private val collector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/") { exchange ->
            received += exchange.requestURI.path to exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.set("Content-Type", "application/x-protobuf")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }

    private val endpoint = "http://127.0.0.1:${collector.address.port}"

    private val ann = MetricLabels(listOf("person" to "ann@example.com", "team" to "core"))

    private val source = ServedMetricSource {
        MetricSnapshot(
            sinceMs = 1_000,
            atMs = 2_000,
            families = listOf(
                MetricFamily.Counter(
                    "splice_server_requests_total",
                    "Requests.",
                    "{request}",
                    listOf(MetricPoint(ann.plus("status_class", "2xx"), 2.0)),
                ),
                MetricFamily.Histogram(
                    "splice_server_ttft_seconds",
                    "Time to first word.",
                    "s",
                    listOf(0.5, 1.0),
                    listOf(HistogramPoint(ann, listOf(1, 1, 0), 1.2)),
                ),
                MetricFamily.Gauge("splice_server_inflight", "In flight.", "{request}", emptyList()),
            ),
        )
    }

    @AfterEach
    fun stop() {
        collector.stop(0)
    }

    @Test
    fun `the collector receives each family with the person and team on its series`() {
        val config = PerfExportConfig(endpoint = endpoint, signals = setOf("metrics"))
        ServedMetricsExport(config, source, LogSink { }).close()

        val (path, body) = received.single()
        assertEquals("/v1/metrics", path)
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("splice_server_requests_total"), "the counter is in the push")
        assertTrue(text.contains("splice_server_ttft_seconds"), "the histogram is in the push")
        assertTrue(text.contains("ann@example.com") && text.contains("core"), "the series carry the person and team")
        assertFalse(text.contains("splice_server_inflight"), "a family with no series is left out")
    }

    @Test
    fun `nothing is sent when the operator's telemetry sends no metrics`() {
        val config = PerfExportConfig(endpoint = endpoint, signals = setOf("traces"))
        ServedMetricsExport(config, source, LogSink { }).close()
        assertTrue(received.isEmpty())
    }
}
