// The lookup of one model on server mode's OpenAI route (docs/specs/server-mode.md, F4), as an OpenAI-style client
// makes it before it uses a model (`client.models.retrieve`), over real HTTP on loopback: a key holder gets the model
// object of a model the command's provider serves, a 404 in OpenAI's error shape for any other id, and nothing without
// a key. Each test reads what the client gets back and what the provider's server was asked.
package splice.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpResponse
import java.nio.file.Path

internal class ServerOpenAiModelTest {
    @TempDir lateinit var state: Path

    private val box = ScriptedBox("box")
    private var opened: OpenAiRig? = null

    /** Server mode over `claude-team`, whose provider speaks openai-chat on [box], and one person's key. */
    private fun serve(): Pair<OpenAiRig, String> {
        val upstreams = object : HeadUpstreams {
            override fun chat(head: String): ChatServer? = BoxServer(box.base).takeIf { head == "team" }
        }
        val rig = OpenAiRig(state, upstreams).also { opened = it }
        return rig to rig.keyFor("ola@example.com")
    }

    @AfterEach
    fun stop() {
        opened?.close()
        box.stop()
    }

    /** The one object [answer]'s body is. */
    private fun body(answer: HttpResponse<String>): JsonObject = Json.parseToJsonElement(answer.body()).jsonObject

    /** The error of [refused], which must be in OpenAI's shape: one `error` object and nothing beside it. */
    private fun openAiError(refused: HttpResponse<String>): JsonObject = openAiError(refused.body())

    @Test
    fun `a model the provider's server lists is answered with that server's own object for it`() {
        val (rig, key) = serve()
        val found = rig.model(key, "team-model")
        assertEquals(200, found.statusCode())
        assertEquals("application/json", found.headers().firstValue("content-type").get().substringBefore(';'))
        assertEquals(Json.parseToJsonElement(TEAM_MODEL), body(found))

        // It is read out of the server's list, asked as the list is: with the provider's key, never the person's.
        val asked = box.asked.single()
        assertEquals("/v1/models", asked.path)
        assertEquals("Bearer $BOX_KEY", asked.headers["authorization"])
        assertFalse(asked.headers.values.any { key in it }, "the person's key stays on this machine")
    }

    @Test
    fun `an id with a slash is one model's id, and an entry the server lists without its kind says it is a model`() {
        val (rig, key) = serve()
        // A client writes the slash as it is, or escaped; both name the same model.
        for (spelled in listOf("team/coder-32b", "team%2Fcoder-32b")) {
            val found = rig.model(key, spelled)
            assertEquals(200, found.statusCode(), found.body())
            assertEquals(JsonPrimitive("team/coder-32b"), body(found)["id"])
            assertEquals(JsonPrimitive("model"), body(found)["object"])
            assertEquals(JsonPrimitive("box"), body(found)["owned_by"])
        }
    }

    @Test
    fun `any other id is a 404 in OpenAI's error shape that names it`() {
        val (rig, key) = serve()
        // The start of an id the server lists is not that id.
        for (id in listOf("gpt-not-served", "team")) {
            val missing = rig.model(key, id)
            assertEquals(404, missing.statusCode())
            val error = openAiError(missing)
            assertEquals("invalid_request_error", error.getValue("type").jsonPrimitive.content)
            assertEquals(JsonNull, error["param"])
            val said = error.getValue("message").jsonPrimitive.content
            assertTrue(said.startsWith("splice server: ") && id in said && "claude-team" in said, said)
        }
    }

    @Test
    fun `without a live key a lookup is refused 401 in OpenAI's error shape, and the provider is asked nothing`() {
        val (rig, _) = serve()
        for (key in listOf(null, "spk_abcdefgh_${"x".repeat(32)}")) {
            val refused = rig.model(key, "team-model")
            assertEquals(401, refused.statusCode())
            assertEquals("invalid_api_key", openAiError(refused).getValue("code").jsonPrimitive.content)
        }
        assertTrue(box.asked.isEmpty(), "a caller without a key reaches no server")
    }

    @Test
    fun `a lookup asked the way Claude Code asks one still goes to the head`() {
        val (rig, key) = serve()
        val asked = rig.model(key, "team-model", "anthropic-version" to "2023-06-01")
        assertEquals(HEAD_NO_ROUTE, asked.body(), "the stand-in head serves no lookup, and says so itself")
        assertTrue(box.asked.isEmpty())
    }

    @Test
    fun `a list the provider's server refuses is the lookup's answer too, in that server's own words`() {
        val (rig, key) = serve()
        box.listRefuses = 503
        val refused = rig.model(key, "team-model")
        assertEquals(503, refused.statusCode())
        assertEquals("""{"error":{"message":"box says 503"}}""", refused.body())
    }
}
