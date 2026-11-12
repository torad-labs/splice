package splice.observability.export

import com.sun.net.httpserver.HttpServer
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ServerCalls
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.export.PerfExportConfig
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfRecorder
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OtlpCollectorsTest {
    @Test
    fun `HTTP protobuf collector receives all three selected signal families`() {
        val received = ConcurrentHashMap<String, ByteArray>()
        val collector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        collector.createContext("/v1/") { exchange ->
            received[exchange.requestURI.path] = exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.set("Content-Type", "application/x-protobuf")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        try {
            exercise("http/protobuf", "http://127.0.0.1:${collector.address.port}")
            assertEquals(setOf("/v1/metrics", "/v1/traces", "/v1/logs"), received.keys)
            assertPayloads(received)
        } finally {
            collector.stop(0)
        }
    }

    @Test
    fun `gRPC collector receives all three signals without HTTP signal suffixes`() {
        val received = ConcurrentHashMap<String, ByteArray>()
        val builder = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
        listOf("metrics" to "Metrics", "trace" to "Trace", "logs" to "Logs").forEach { (namespace, type) ->
            val name = "opentelemetry.proto.collector.$namespace.v1.${type}Service"
            val method = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
                .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("$name/Export")
                .setRequestMarshaller(BytesMarshaller).setResponseMarshaller(BytesMarshaller).build()
            builder.addService(
                ServerServiceDefinition.builder(name).addMethod(
                    method,
                    ServerCalls.asyncUnaryCall { request, observer ->
                        received[name] = request
                        observer.onNext(byteArrayOf())
                        observer.onCompleted()
                    },
                ).build(),
            )
        }
        val collector = builder.build().start()
        try {
            // A first gRPC call loads the client's and the collector's classes, and on a coverage runner that took
            // longer than the 750 ms an attempt is given: on Nov 11, 2026 metrics and traces, sent first, failed there
            // twice while logs, sent last, arrived. One export nobody judges goes first, so the one judged meets a
            // collector that already answers.
            exercise("grpc", "http://127.0.0.1:${collector.port}", judged = false)
            received.clear()
            exercise("grpc", "http://127.0.0.1:${collector.port}")
            assertEquals(3, received.size)
            assertPayloads(received)
        } finally {
            collector.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `disabled endpoint or signals makes no network request and outage is visible`() {
        val received = ConcurrentHashMap<String, ByteArray>()
        val collector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        collector.createContext("/") { exchange ->
            received[exchange.requestURI.path] = exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        collector.start()
        try {
            val endpoint = "http://127.0.0.1:${collector.address.port}"
            listOf(
                PerfExportConfig(endpoint = endpoint),
                PerfExportConfig(signals = setOf("metrics", "traces", "logs")),
            ).forEach { config ->
                PerfExports(
                    config,
                    ExportFixtures.identity,
                    PerfAggregateSource { ExportFixtures.snapshot() },
                ).use { exports ->
                    assertTrue(exports.record(ExportFixtures.event(PerfEventKind.REQUEST_FINISHED)))
                    assertTrue(exports.flush())
                }
            }
            assertTrue(received.isEmpty())
            PerfExports(
                PerfExportConfig(endpoint = endpoint, signals = setOf("metrics")),
                ExportFixtures.identity,
                PerfAggregateSource { ExportFixtures.snapshot() },
            ).use { exports ->
                assertFalse(exports.flush())
                ExportStatusRead(exports).use { status ->
                    assertTrue(status.value.getValue("metrics").jsonObject.getValue("failed").jsonPrimitive.long > 0)
                    assertFalse(status.value.toString().contains(endpoint))
                }
            }
            assertTrue(received.isNotEmpty())
        } finally {
            collector.stop(0)
        }
    }

    private fun exercise(protocol: String, endpoint: String, judged: Boolean = true) {
        val config = PerfExportConfig(
            endpoint = endpoint,
            protocol = protocol,
            headers = mapOf("authorization" to "Bearer header-secret"),
            signals = setOf("metrics", "traces", "logs"),
        )
        PerfExports(
            config,
            ExportFixtures.identity,
            PerfAggregateSource {
                ExportFixtures.snapshot()
            },
            WallClock { 2_000 },
        ).use { exports ->
            val recorder = PerfRecorder(ExportFixtures.identity, exports, ElapsedClock { 1_250 }, WallClock { 1_250 })
            val request = recorder.request(ExportFixtures.scope, 1_000, 1_000)
            request.update(request.scope, ExportFixtures.links)
            assertTrue(request.finish("ok"))
            val flushed = exports.flush()
            if (!judged) return@use
            ExportStatusRead(exports).use { status ->
                assertTrue(flushed, status.value.toString())
                assertFalse(status.value.toString().contains("header-secret"))
            }
        }
    }

    private fun assertPayloads(received: Map<String, ByteArray>) {
        assertTrue(received.values.all { it.isNotEmpty() })
        val bytes = received.values.joinToString { it.toString(Charsets.UTF_8) }
        assertTrue(bytes.contains("splice_requests_finished"))
        assertTrue(bytes.contains("splice.request"))
        assertTrue(bytes.contains("REQUEST_FINISHED"))
        assertFalse(bytes.contains("secret"))
    }
}

private object BytesMarshaller : MethodDescriptor.Marshaller<ByteArray> {
    override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
    override fun parse(stream: InputStream): ByteArray = stream.readBytes()
}
