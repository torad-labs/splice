// UpstreamPlaygroundProbe against providers built by the daemon's own ProviderAssembly from a real TopologyLoader.parse:
// the credential is redacted in the echoed request, the provider's answer comes back as it came, the model the caller
// names is sent, and every failure (no provider, forwarded auth, no credential, unreadable credential, upstream error)
// is a named PlaygroundFailure. What the probe sends is held to the turn path by PlaygroundTurnParityTest.
package splice.app.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import java.nio.file.Path

class UpstreamPlaygroundProbeTest {

    @Test
    fun `the credential never echoes back, and the provider's answer comes back as it came`(@TempDir root: Path) =
        runTest {
            val engine = MockEngine {
                respond(
                    content = """{"error":{"message":"Input must be a list"}}""",
                    status = HttpStatusCode.BadRequest,
                )
            }
            val outcome = UpstreamPlaygroundProbe(registered(root), HttpClient(engine))
                .run(playgroundHead("anthropicy"), "hello", null)

            val result = outcome as PlaygroundResult
            val echoed = result.request.jsonObject["headers"]!!.jsonObject
            assertEquals(HeaderRedaction.REDACTED, echoed["x-api-key"]!!.jsonPrimitive.content)
            val version = echoed["anthropic-version"]!!.jsonPrimitive.content
            assertEquals("2023-06-01", version, "a plain header reads as sent")
            assertEquals(400, result.response.jsonObject["status"]!!.jsonPrimitive.content.toInt())
            val error = result.response.jsonObject["body"]!!.jsonObject["error"]!!.jsonObject
            assertEquals("Input must be a list", error["message"]!!.jsonPrimitive.content)
        }

    /** The Playground compares models, two on one command as readily as two commands, so a run names its
     *  model and the head's pinned model is only the default. */
    @Test
    fun `a model the caller names is sent in place of the head's pinned one`(@TempDir root: Path) = runTest {
        val sent = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            sent += body["model"]!!.jsonPrimitive.content
            respond(content = "{}", status = HttpStatusCode.OK)
        }
        val probe = UpstreamPlaygroundProbe(registered(root), HttpClient(engine))
        probe.run(playgroundHead("chatty"), "hello", "chat-model-9")
        probe.run(playgroundHead("chatty"), "hello", null)
        assertEquals(listOf("chat-model-9", "chat-model"), sent, "the named model, then the pinned one")
    }

    @Test
    fun `every named failure answers a PlaygroundFailure, never a thrown exception`(@TempDir root: Path) = runTest {
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.OK) })
        val probe = UpstreamPlaygroundProbe(registered(root), client)

        val unknown = probe.run(playgroundHead("chatty").copy(key = "no-such-head"), "hi", null)
        assertTrue((unknown as PlaygroundFailure).message.contains("has no running provider"), unknown.message)

        val forwarded = probe.run(playgroundHead("anthropicy", Credentials.ClientForwarded), "hi", null)
        assertTrue((forwarded as PlaygroundFailure).message.contains("forwards the caller's own auth"))

        val noCred = probe.run(playgroundHead("anthropicy", null), "hi", null)
        assertTrue((noCred as PlaygroundFailure).message.contains("has no credential configured"))

        val unreadable = PlaygroundHead(
            "chatty",
            object : AuthProvider {
                override suspend fun credentials(): Credentials = throw java.io.IOException("keyring locked")
                override suspend fun describe() = AuthDescription(false, "synthetic", emptyMap())
            },
        )
        val threw = probe.run(unreadable, "hi", null)
        assertTrue((threw as PlaygroundFailure).message.contains("reading credentials failed"), threw.message)
    }

    @Test
    fun `an upstream failure is a named PlaygroundFailure, not a crash`(@TempDir root: Path) = runTest {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val probe = UpstreamPlaygroundProbe(registered(root), HttpClient(engine))
        val outcome = probe.run(playgroundHead("chatty"), "hi", null)
        assertTrue((outcome as PlaygroundFailure).message.contains("upstream call"), outcome.message)
    }
}
