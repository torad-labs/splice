// NEW: V4-133 — UpstreamPlaygroundProbe against a REAL TopologyLoader.parse (unlike :daemon-control, :app
// carries the real ktoml parser), so a head/provider lookup that only works against a hand-built
// Topology object would still be a lie about production. Covers the three dialects' URL and body
// shape, the auth header being redacted in the echoed request, and every failure named by
// UpstreamPlaygroundProbe.kt: no topology file, an unknown head, an undeclared provider,
// client-forwarded auth, no credential, and the upstream call itself failing.
package splice.app.probe

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
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.diagnostics.playground.PlaygroundFailure
import splice.diagnostics.playground.PlaygroundHead
import splice.diagnostics.playground.PlaygroundResult
import splice.upstream.transport.HeaderRedaction
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

    private fun head(key: String, creds: Credentials? = defaultCreds): PlaygroundHead = PlaygroundHead(
        key = key,
        auth = object : AuthProvider {
            override suspend fun credentials(): Credentials? = creds
            override suspend fun describe() = AuthDescription(creds != null, "test", emptyMap())
        },
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
        val outcome = probe.run(head("claude"), "hello", null)

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
        probe.run(head("chatty"), "hello", null)
        val sent = requireNotNull(captured)
        assertEquals("https://chat.example.com/v1/chat/completions", sent.url.toString())
        val body = Json.parseToJsonElement((sent.body as TextContent).text).jsonObject
        assertEquals("m2", body["model"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("messages"), body.toString())
    }

    /** V4-444: the Playground compares models, two on one command as readily as two commands, so a run
     *  names its model and the head's pinned model is only the default. */
    @Test
    fun `a model the caller names is sent in place of the head's pinned one`(@TempDir tmp: Path) = runTest {
        val sent = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            sent += body["model"]!!.jsonPrimitive.content
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val probe = UpstreamPlaygroundProbe(configFile(tmp), HttpClient(engine))
        probe.run(head("chatty"), "hello", "m9")
        probe.run(head("chatty"), "hello", null)
        assertEquals(listOf("m9", "m2"), sent, "the named model, then the pinned one when none is named")
    }

    @Test
    fun `openai-responses posts input as a plain string`(@TempDir tmp: Path) = runTest {
        var captured: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val probe = UpstreamPlaygroundProbe(configFile(tmp), HttpClient(engine))
        probe.run(head("respy"), "hello", null)
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

        val noFile = UpstreamPlaygroundProbe(null, client).run(head("claude"), "hi", null)
        assertTrue((noFile as PlaygroundFailure).message.contains("no topology file"), noFile.message)

        val unknownHead = UpstreamPlaygroundProbe(file, client).run(head("no-such-head"), "hi", null)
        assertTrue((unknownHead as PlaygroundFailure).message.contains("is not in the current topology"))

        val undeclaredProvider = UpstreamPlaygroundProbe(file, client).run(head("orphan"), "hi", null)
        assertTrue((undeclaredProvider as PlaygroundFailure).message.contains("is not declared"))

        val forwarded = UpstreamPlaygroundProbe(file, client)
            .run(head("claude", Credentials.ClientForwarded), "hi", null)
        assertTrue((forwarded as PlaygroundFailure).message.contains("forwards the caller's own auth"))

        val noCred = UpstreamPlaygroundProbe(file, client).run(head("claude", null), "hi", null)
        assertTrue((noCred as PlaygroundFailure).message.contains("has no credential configured"))
    }

    @Test
    fun `an upstream failure is a named PlaygroundFailure, not a crash`(@TempDir tmp: Path) = runTest {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val probe = UpstreamPlaygroundProbe(configFile(tmp), HttpClient(engine))
        val outcome = probe.run(head("claude"), "hi", null)
        assertTrue((outcome as PlaygroundFailure).message.contains("upstream call"), outcome.message)
    }
}
