package splice.server.ledger

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.core.topology.ServerConfig
import splice.server.HeadUpstreams
import splice.server.ServerRig
import splice.server.SetClock
import splice.server.StandInHead
import splice.upstream.Waiter
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class ExternalMeterReportTest {
    @TempDir lateinit var state: Path

    private val configJson = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()
    private val clock = SetClock()
    private val ticks = MeterTicks()
    private val rig = ServerRig(
        clock = clock,
        waiter = ticks,
        upstreams = object : HeadUpstreams {
            override fun hourly(): Map<String, Double> = mapOf("host" to 1.0)
        },
    )

    @AfterEach
    fun close() = rig.close()

    @Test
    fun `the report keeps distinct priced intervals across failures, restarts and rate changes`() {
        val signal = state.resolve("running.txt")
        Files.writeString(signal, "true\n")
        val key = open(config(60))
        val at = clock.instant()
        ticks.awaitSamples(2)
        assertEquals(setOf("host", "gpu", "disk"), fixed(report(key, at.toString())).keys)

        advance()
        val first = report(key, at.toString())
        assertCost(first, "gpu", 1.0)
        assertCost(first, "disk", 0.5)
        assertCost(report(key, at.plusSeconds(30).toString()), "gpu", 0.5)
        assertEquals(
            fixed(first).values.sumOf { it.getValue("cost_usd").jsonPrimitive.double },
            first.getValue("totals").jsonObject.getValue("fixed_usd").jsonPrimitive.double,
            1e-9,
        )

        Files.writeString(signal, "not a signal")
        advance()
        Files.writeString(signal, "true")
        advance()
        assertCost(report(key, at.toString()), "gpu", 1.0)

        rig.close()
        clock.pass(3600)
        open(config(120))
        ticks.awaitSamples(2)
        assertCost(report(key, at.toString()), "gpu", 1.0)
        advance()
        assertCost(report(key, at.toString()), "gpu", 3.0)

        rig.close()
        open(config(120, keepGpu = false))
        ticks.awaitSamples(1)
        assertCost(report(key, at.toString()), "gpu", 3.0)
    }

    @Test
    fun `without cost meters the report and state keep their provider-only defaults`() {
        val key = open(rig.config.copy(admins = listOf("ops@team.example")))
        val costs = fixed(report(key, clock.instant().minusSeconds(60).toString()))
        assertEquals(setOf("host"), costs.keys)
        assertEquals(setOf("provider", "hours", "cost_usd"), costs.getValue("host").keys)
        assertFalse(Files.exists(state.resolve("server/cost-meters.jsonl")))
    }

    @Test
    fun `only successful URL responses advance the HTTP meter`() {
        val status = AtomicInteger(204)
        val signal = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        signal.createContext("/") { exchange ->
            // If redirects were followed, the failure would be misread as running.
            val result = if (exchange.requestURI.path == "/always-up") 204 else status.get()
            if (result == 302) exchange.responseHeaders.set("Location", "/always-up")
            exchange.sendResponseHeaders(result, -1)
            exchange.close()
        }
        signal.start()
        try {
            val url = "http://127.0.0.1:${signal.address.port}/health"
            val config = configJson.decodeFromString<ServerConfig>(
                """{"cost_meters":{"service":{"hourly_usd":60,"url":"$url"}}}""",
            )
            val configured = config.copy(
                listen = rig.config.listen,
                commands = rig.config.commands,
                admins = listOf("ops@team.example"),
            )
            val key = open(configured)
            val from = clock.instant().toString()
            ticks.awaitSamples(1)
            advance(1)
            assertCost(report(key, from), "service", 1.0)
            status.set(503)
            advance(1)
            status.set(302)
            advance(1)
            status.set(204)
            advance(1)
            assertCost(report(key, from), "service", 1.0)
            advance(1)
            assertCost(report(key, from), "service", 2.0)
        } finally {
            signal.stop(0)
        }
    }

    private fun config(rate: Int, keepGpu: Boolean = true): ServerConfig {
        val gpu = if (keepGpu) """ "gpu":{"hourly_usd":$rate,"file":"running.txt"}, """ else ""
        val meters = """{"cost_meters":{$gpu "disk":{"hourly_usd":30,"argv":["/usr/bin/true"]}}}"""
        return configJson.decodeFromString<ServerConfig>(meters).copy(
            listen = rig.config.listen,
            commands = rig.config.commands,
            admins = listOf("ops@team.example"),
        )
    }

    private fun open(config: ServerConfig): String {
        rig.open(state, listOf(StandInHead(TestPorts.reserve())), config)
        return rig.keyFor("ops@team.example")
    }

    private fun advance(count: Int = 2) {
        clock.pass(60)
        ticks.advance()
        ticks.awaitSamples(count)
    }

    private fun report(key: String, from: String): JsonObject {
        val to = clock.instant().plusSeconds(1)
        val url = URI.create("http://127.0.0.1:${rig.port}/server/api/usage?from=$from&to=$to")
        val request = HttpRequest.newBuilder(url).header("Authorization", "Bearer $key").build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    private fun assertCost(report: JsonObject, name: String, expected: Double) {
        assertEquals(expected, fixed(report).getValue(name).getValue("cost_usd").jsonPrimitive.double, 1e-9)
    }

    private fun fixed(report: JsonObject): Map<String, JsonObject> =
        report.getValue("fixed").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("provider").jsonPrimitive.content }
}

private class MeterTicks : Waiter {
    private val waiting = LinkedBlockingQueue<Channel<Unit>>()
    private var held = emptyList<Channel<Unit>>()

    override suspend fun wait(ms: Long) {
        val tick = Channel<Unit>(1)
        waiting.add(tick)
        tick.receive()
    }

    fun awaitSamples(count: Int) {
        held = List(count) { checkNotNull(waiting.poll(5, TimeUnit.SECONDS)) { "meter did not sample" } }
    }

    fun advance() = held.forEach { check(it.trySend(Unit).isSuccess) }
}
