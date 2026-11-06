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
                assertTrue(exports.status().getValue("metrics").jsonObject.getValue("failed").jsonPrimitive.long > 0)
                assertFalse(exports.status().toString().contains(endpoint))
            }
            assertTrue(received.isNotEmpty())
        } finally {
            collector.stop(0)
        }
    }

    private fun exercise(protocol: String, endpoint: String) {
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
            assertTrue(exports.record(ExportFixtures.event(PerfEventKind.REQUEST_ARRIVED)))
            assertTrue(exports.record(ExportFixtures.event(PerfEventKind.REQUEST_FINISHED, 1_250)))
            assertTrue(exports.flush(), exports.status().toString())
            assertFalse(exports.status().toString().contains("header-secret"))
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
