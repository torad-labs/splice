// NEW: V4-133 — UpstreamPlaygroundProbe against a REAL TopologyLoader.parse (unlike :control, :app
// carries the real ktoml parser), so a head/provider lookup that only works against a hand-built
// Topology object would still be a lie about production. Covers the three dialects' URL and body
// shape, the auth header being redacted in the echoed request, and every failure named by
// UpstreamPlaygroundProbe.kt: no topology file, an unknown head, an undeclared provider,
// client-forwarded auth, no credential, and the upstream call itself failing.
package console.v4133

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.UpstreamPlaygroundProbe
import splice.control.CompactView
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadUsageSource
import splice.control.ManagedHead
import splice.control.RateLimitView
import splice.control.UsageView
import splice.control.api.PlaygroundFailure
import splice.control.api.PlaygroundResult
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.spi.HeaderRedaction
import java.nio.file.Files
import java.nio.file.Path

private const val TOML = """
[providers.anthro]
dialect = "anthropic-passthrough"
base_url = "https://api.example.com"
auth = { kind = "api-key", env = "P_KEY" }
extra_headers = { "anthropic-version" = "2023-06-01" }

[providers.chat]
dialect = "openai-chat"
base_url = "https://chat.example.com/v1"
auth = { kind = "api-key", env = "C_KEY" }

[providers.resp]
dialect = "openai-responses"
base_url = "https://resp.example.com/v1"
auth = { kind = "api-key", env = "R_KEY" }

[heads.claude]
provider = "anthro"
port = 9001
discovery_prefix = "claude/"
pinned_model = "m1"

[heads.chatty]
provider = "chat"
port = 9002
discovery_prefix = "chatty/"
pinned_model = "m2"

[heads.respy]
provider = "resp"
port = 9003
discovery_prefix = "respy/"
pinned_model = "m3"

[heads.orphan]
provider = "ghost"
port = 9004
discovery_prefix = "orphan/"
pinned_model = "m4"
"""

private val defaultCreds: Credentials = Credentials.ApiKey("secret-key", "x-api-key", "")

class UpstreamPlaygroundProbeTest {

    private fun head(key: String, creds: Credentials? = defaultCreds): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = key
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, 0, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials(): Credentials? = creds
            override suspend fun describe() = AuthDescription(creds != null, "test", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
    )

    private fun configFile(tmp: Path): Path = tmp.resolve("splice.toml").also { Files.writeString(it, TOML) }

    @Test
    fun `anthropic-passthrough posts v1 messages, redacts the auth header, and merges static headers`(
        @TempDir tmp: Path,
    ) = runTest {
        var captured: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(content = """{"content":[{"text":"hi back"}]}""", status = HttpStatusCode.OK)
        }
        val probe = UpstreamPlaygroundProbe(configFile(tmp), HttpClient(engine))
        val outcome = probe.run(head("claude"), "hello")

        val sent = requireNotNull(captured)
        assertEquals("https://api.example.com/v1/messages", sent.url.toString())
        assertEquals("2023-06-01", sent.headers["anthropic-version"])
        assertEquals("secret-key", sent.headers["x-api-key"], "the real upstream call carries the real credential")
        val body = Json.parseToJsonElement((sent.body as TextContent).text).jsonObject
        assertEquals("m1", body["model"]!!.jsonPrimitive.content)
        val message = (body["messages"] as JsonArray).single().jsonObject
        assertEquals("hello", message["content"]!!.jsonPrimitive.content)

        assertTrue(outcome is PlaygroundResult, "expected a result: $outcome")
        val result = outcome as PlaygroundResult
        val echoedHeaders = result.request.jsonObject["headers"]!!.jsonObject
        assertEquals(
            HeaderRedaction.REDACTED,
            echoedHeaders["x-api-key"]!!.jsonPrimitive.content,
            "the credential never echoes back",
        )
        val responseText = (result.response.jsonObject["body"]!!.jsonObject["content"] as JsonArray).single().jsonObject
        assertEquals("hi back", responseText["text"]!!.jsonPrimitive.content)
        assertEquals(200, result.response.jsonObject["status"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `openai-chat posts chat completions with a messages array`(@TempDir tmp: Path) = runTest {
        var captured: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val probe = UpstreamPlaygroundProbe(configFile(tmp), HttpClient(engine))
        probe.run(head("chatty"), "hello")
        val sent = requireNotNull(captured)
        assertEquals("https://chat.example.com/v1/chat/completions", sent.url.toString())
        val body = Json.parseToJsonElement((sent.body as TextContent).text).jsonObject
        assertEquals("m2", body["model"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("messages"), body.toString())
    }

    @Test
    fun `openai-responses posts input as a plain string`(@TempDir tmp: Path) = runTest {
        var captured: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val probe = UpstreamPlaygroundProbe(configFile(tmp), HttpClient(engine))
        probe.run(head("respy"), "hello")
        val sent = requireNotNull(captured)
        assertEquals("https://resp.example.com/v1/responses", sent.url.toString())
        val body = Json.parseToJsonElement((sent.body as TextContent).text).jsonObject
        assertEquals("m3", body["model"]!!.jsonPrimitive.content)
        assertEquals("hello", body["input"]!!.jsonPrimitive.content)
    }

    @Test
    fun `every named failure answers a PlaygroundFailure, never a thrown exception`(@TempDir tmp: Path) = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val client = HttpClient(engine)
        val file = configFile(tmp)

        val noFile = UpstreamPlaygroundProbe(null, client).run(head("claude"), "hi")
        assertTrue((noFile as PlaygroundFailure).message.contains("no topology file"), noFile.message)

        val unknownHead = UpstreamPlaygroundProbe(file, client).run(head("no-such-head"), "hi")
        assertTrue((unknownHead as PlaygroundFailure).message.contains("is not in the current topology"))

        val undeclaredProvider = UpstreamPlaygroundProbe(file, client).run(head("orphan"), "hi")
        assertTrue((undeclaredProvider as PlaygroundFailure).message.contains("is not declared"))

        val forwarded = UpstreamPlaygroundProbe(file, client).run(head("claude", Credentials.ClientForwarded), "hi")
        assertTrue((forwarded as PlaygroundFailure).message.contains("forwards the caller's own auth"))

        val noCred = UpstreamPlaygroundProbe(file, client).run(head("claude", null), "hi")
        assertTrue((noCred as PlaygroundFailure).message.contains("has no credential configured"))
    }

    @Test
    fun `an upstream failure is a named PlaygroundFailure, not a crash`(@TempDir tmp: Path) = runTest {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val probe = UpstreamPlaygroundProbe(configFile(tmp), HttpClient(engine))
        val outcome = probe.run(head("claude"), "hi")
        assertTrue((outcome as PlaygroundFailure).message.contains("upstream call"), outcome.message)
    }
}
