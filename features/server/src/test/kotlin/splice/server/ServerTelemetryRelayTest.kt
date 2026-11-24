// The telemetry relay (docs/specs/server-mode.md, F8), asked over the listener as a laptop's Claude Code asks it, with a
// collector of the test's own behind it: what the collector receives is the client's request with the person and their
// team on every resource, the rest carried as it came, in a gzipped body too; and the profile names the relay at the
// address the laptop reached the server at, as a trusted proxy says it, and nobody else.
package splice.server

import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.core.topology.ServerTeams
import splice.core.topology.ServerTelemetry
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

internal class ServerTelemetryRelayTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val configJson = Json { ignoreUnknownKeys = true }
    private val rig = ServerRig()
    private val received = LinkedBlockingQueue<Pair<Map<String, List<String>>, ByteArray>>()
    private val collector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            received.add(exchange.requestHeaders.mapKeys { it.key.lowercase() } to exchange.requestBody.readAllBytes())
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }

    @AfterEach
    fun stop() {
        rig.close()
        collector.stop(0)
    }

    private fun open(trustedProxies: List<String> = emptyList(), custom: Boolean = false): String {
        val url = "http://127.0.0.1:${collector.address.port}/"
        val telemetry = if (custom) {
            configJson.decodeFromString<ServerTelemetry>(
                """{"forward_to":"$url",
                    "identity_attributes":{"person":"org.actor","email":"org.email","team":"org.team"}}""",
            )
        } else {
            ServerTelemetry(url)
        }
        val teams = ServerTeams(mapOf("team.example" to "core"))
        val server = rig.config.copy(telemetry = telemetry, teams = teams, trustedProxies = trustedProxies)
        rig.open(state, listOf(StandInHead(TestPorts.reserve())), server)
        return rig.keyFor("ann@team.example")
    }

    private fun post(signal: String, type: String, body: ByteArray, key: String, gzip: Boolean = false): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/server/otlp/v1/$signal"))
            .header("content-type", type)
            .header("authorization", "Bearer $key")
        if (gzip) request.header("content-encoding", "gzip")
        val sent = if (gzip) gzipped(body) else body
        val posted = request.POST(HttpRequest.BodyPublishers.ofByteArray(sent)).build()
        return http.send(posted, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun next(): Pair<Map<String, List<String>>, ByteArray> =
        checkNotNull(received.poll(10, TimeUnit.SECONDS)) { "the collector received nothing" }

    @Test
    fun `a gzipped JSON request reaches the collector stamped, inflated, and without the person's key`() {
        val key = open()
        val body = """{"resourceLogs":[{"resource":{"attributes":[
            {"key":"user.email","value":{"stringValue":"mallory@evil.test"}},
            {"key":"service.name","value":{"stringValue":"claude-code"}}]},
            "scopeLogs":[{"logRecords":[{"body":{"stringValue":"record-one"}}]}]}]}"""
        assertEquals(200, post("logs", "application/json", body.encodeToByteArray(), key, gzip = true))
        val (headers, got) = next()
        assertNull(headers["content-encoding"], "the collector gets the body as it reads it")
        assertEquals(null, headers["authorization"])
        val entry = Json.parseToJsonElement(got.decodeToString()).jsonObject.getValue("resourceLogs").jsonArray.single()
        val attributes = entry.jsonObject.getValue("resource").jsonObject.getValue("attributes").jsonArray.associate {
            it.jsonObject.getValue("key").jsonPrimitive.content to
                it.jsonObject.getValue("value").jsonObject.getValue("stringValue").jsonPrimitive.content
        }
        val stamped = mapOf(
            "service.name" to "claude-code",
            "splice.person" to "ann@team.example",
            "user.email" to "ann@team.example",
            "splice.team" to "core",
        )
        assertEquals(stamped, attributes)
        assertEquals(
            "record-one",
            entry.jsonObject.getValue("scopeLogs").jsonArray.single().jsonObject.getValue("logRecords").jsonArray
                .single().jsonObject.getValue("body").jsonObject.getValue("stringValue").jsonPrimitive.content,
        )
    }

    @Test
    fun `a protobuf request keeps its records byte for byte, and an entry with no resource gets one`() {
        val key = open()
        val scope = message(1 to message(1 to "com.anthropic.claude_code".utf8()), 2 to message(1 to "span".utf8()))
        val withClaim = message(1 to message(1 to keyValue("splice.team", "red")), 2 to scope)
        val bare = message(2 to scope)
        val body = message(1 to withClaim, 1 to bare).toByteArray()
        assertEquals(200, post("traces", "application/x-protobuf", body, key))
        val entries = UnknownFieldSet.parseFrom(next().second).getField(1).lengthDelimitedList
        assertEquals(2, entries.size)
        for (entry in entries) {
            val fields = UnknownFieldSet.parseFrom(entry)
            assertArrayEquals(scope.toByteArray(), fields.getField(2).lengthDelimitedList.single().toByteArray())
            val resource = UnknownFieldSet.parseFrom(fields.getField(1).lengthDelimitedList.single())
            val attributes = resource.getField(1).lengthDelimitedList.map(::readKeyValue).toMap()
            val person = "ann@team.example"
            assertEquals(mapOf("splice.person" to person, "user.email" to person, "splice.team" to "core"), attributes)
        }
    }

    @Test
    fun `renamed attributes replace client claims in JSON and protobuf`() {
        val key = open(custom = true)
        val body = """{"resourceSpans":[{"resource":{"attributes":[
            {"key":"org.actor","value":{"stringValue":"claimed"}},
            {"key":"splice.team","value":{"stringValue":"claimed"}},
            {"key":"service.name","value":{"stringValue":"client"}}]}}]}"""
        val expected = mapOf(
            "org.actor" to "ann@team.example",
            "org.email" to "ann@team.example",
            "org.team" to "core",
            "service.name" to "client",
        )
        assertEquals(200, post("traces", "application/json", body.encodeToByteArray(), key))
        val entry = Json.parseToJsonElement(next().second.decodeToString()).jsonObject
            .getValue("resourceSpans").jsonArray.single()
        val attributes = entry.jsonObject.getValue("resource").jsonObject.getValue("attributes").jsonArray
        val jsonStamp = attributes.associate {
            it.jsonObject.getValue("key").jsonPrimitive.content to
                it.jsonObject.getValue("value").jsonObject.getValue("stringValue").jsonPrimitive.content
        }
        assertEquals(expected, jsonStamp)

        val protoResource = message(
            1 to keyValue("org.actor", "claimed"),
            1 to keyValue("splice.team", "claimed"),
            1 to keyValue("service.name", "client"),
        )
        val proto = message(1 to message(1 to protoResource))
        assertEquals(200, post("traces", "application/x-protobuf", proto.toByteArray(), key))
        val stamped = UnknownFieldSet.parseFrom(next().second)
        val resource = (1..2).fold(stamped) { value, _ ->
            UnknownFieldSet.parseFrom(value.getField(1).lengthDelimitedList.single())
        }
        assertEquals(expected, resource.getField(1).lengthDelimitedList.map(::readKeyValue).toMap())
    }

    @Test
    fun `the profile names the relay where a trusted proxy says the laptop reached the server, and no one else`() {
        val key = open(trustedProxies = listOf("127.0.0.1"))
        val proxy = mapOf("X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "gateway.team.example")
        assertEquals("https://gateway.team.example/server/otlp", profileRelay(key, proxy))
        rig.close()
        val untrusted = open()
        val direct = profileRelay(untrusted, mapOf("X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "evil.test"))
        assertEquals("http://127.0.0.1:${rig.port}/server/otlp", direct)
    }

    private fun profileRelay(key: String, headers: Map<String, String>): String {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/server/profile"))
            .header("authorization", "Bearer $key")
        headers.forEach { (name, value) -> request.header(name, value) }
        val answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, answer.statusCode(), answer.body())
        val profile = Json.parseToJsonElement(answer.body()).jsonObject
        return profile.getValue("telemetry").jsonObject.getValue("otlp").jsonPrimitive.content
    }

    private fun gzipped(body: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(body) } }.toByteArray()

    private fun String.utf8(): ByteString = ByteString.copyFromUtf8(this)

    private fun message(vararg fields: Pair<Int, Any>): UnknownFieldSet {
        val builder = UnknownFieldSet.newBuilder()
        for ((number, value) in fields) {
            val bytes = if (value is UnknownFieldSet) value.toByteString() else value as ByteString
            builder.mergeField(number, UnknownFieldSet.Field.newBuilder().addLengthDelimited(bytes).build())
        }
        return builder.build()
    }

    private fun keyValue(key: String, value: String): UnknownFieldSet =
        message(1 to key.utf8(), 2 to message(1 to value.utf8()))

    private fun readKeyValue(bytes: ByteString): Pair<String, String> {
        val kv = UnknownFieldSet.parseFrom(bytes)
        val any = UnknownFieldSet.parseFrom(kv.getField(2).lengthDelimitedList.single())
        return kv.getField(1).lengthDelimitedList.single().toStringUtf8() to
            any.getField(1).lengthDelimitedList.single().toStringUtf8()
    }
}
