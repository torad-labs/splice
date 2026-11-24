// #791 over real HTTP on loopback, as a client meets it: `[server.aliases]` lets an OpenAI- or Anthropic-style client
// that cannot change its base path reach a served command at `/v1/...`, under the same key, caps and minimum client;
// and `[server.client]` judges a laptop by a launcher's own header or a check-in, answering an older one in the
// conversation or refusing it with the status and words the operator set.
package splice.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.client.SPLICE_CLIENT_HEADER
import splice.core.topology.ServerClient
import splice.core.topology.ServerConfig
import splice.core.topology.ServerLimits
import java.net.http.HttpResponse
import java.nio.file.Path

internal class ServerAliasClientTest {
    @TempDir lateinit var state: Path

    private val box = ScriptedBox("box")
    private var opened: OpenAiRig? = null

    /** `claude-team` on [box], with `/v1` its alias, and `[server]` as [configured] sets it beyond that. */
    private fun serve(configured: (ServerConfig) -> ServerConfig = { it }): OpenAiRig {
        val upstreams = object : HeadUpstreams {
            override fun chat(head: String): ChatServer? = BoxServer(box.base).takeIf { head == "team" }
        }
        val aliased = mapOf(V1_ALIAS to "claude-team")
        return OpenAiRig(state, upstreams, ServerLimits(), configured = { configured(it.copy(aliases = aliased)) })
            .also { opened = it }
    }

    @AfterEach
    fun stop() {
        opened?.close()
        box.stop()
    }

    private fun text(message: HttpResponse<String>): String {
        val body = Json.parseToJsonElement(message.body()).jsonObject
        return body.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    }

    private fun chatText(completion: HttpResponse<String>): String {
        val choice = Json.parseToJsonElement(completion.body()).jsonObject.getValue("choices").jsonArray.single()
        return choice.jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
    }

    @Test
    fun `an OpenAI client at the alias gets the command's chat completions and models, as at its own path`() {
        val rig = serve()
        val key = rig.keyFor("ola@example.com")
        val completed = rig.ask("/v1/chat/completions", key, CHAT)
        assertEquals(200, completed.statusCode(), completed.body())
        assertTrue("from box" in completed.body(), completed.body())
        assertEquals("/v1/chat/completions", box.asked.single().path)
        val listed = rig.ask("/v1/models", key)
        assertEquals(200, listed.statusCode(), listed.body())
        assertEquals(BOX_MODELS, listed.body())
        val looked = rig.ask("/v1/models/team-model", key)
        assertEquals(Json.parseToJsonElement(TEAM_MODEL), Json.parseToJsonElement(looked.body()))
    }

    @Test
    fun `an Anthropic client at the alias gets the command's Messages turn, count and models from its head`() {
        val rig = serve()
        val key = rig.keyFor("ola@example.com")
        val turn = rig.ask("/v1/messages", key, TURN, listOf("anthropic-version" to "1"))
        assertEquals(200, turn.statusCode(), turn.body())
        assertEquals("from the head", text(turn))
        val counted = rig.ask("/v1/messages/count_tokens", key, TURN)
        assertEquals("""{"input_tokens":3}""", counted.body())
        assertEquals(HEAD_MODELS, rig.ask("/v1/models", key, extra = listOf("anthropic-version" to "1")).body())
        assertTrue(box.asked.isEmpty(), "Claude Code's routes are the head's, never the provider's")
    }

    @Test
    fun `an alias asks for a key as the command's own path does`() {
        val rig = serve()
        val refused = rig.ask("/v1/chat/completions", key = null, body = CHAT)
        assertEquals(401, refused.statusCode())
        assertEquals("invalid_api_key", openAiError(refused.body()).getValue("code").jsonPrimitive.content)
        val unknown = rig.ask("/v1/chat/completions", "spk_abcdefgh_${"x".repeat(32)}", CHAT)
        assertEquals(401, unknown.statusCode())
        assertEquals("invalid_api_key", openAiError(unknown.body()).getValue("code").jsonPrimitive.content)
        assertTrue(box.asked.isEmpty())
    }

    @Test
    fun `an older client's chat completion at the alias is told in the conversation and reaches no provider`() {
        val rig = serve { it.copy(client = ServerClient(MINIMUM)) }
        val key = rig.keyFor("ola@example.com")
        val told = rig.ask("/v1/chat/completions", key, CHAT, listOf(SPLICE_CLIENT_HEADER to "0.9.0"))
        assertEquals(200, told.statusCode(), told.body())
        val said = chatText(told)
        assertTrue("splice 0.9.0" in said && MINIMUM in said && "splice upgrade" in said, said)
        val streamed = rig.ask("/v1/chat/completions", key, STREAMED_CHAT, listOf(SPLICE_CLIENT_HEADER to "0.9.0"))
        assertTrue(streamed.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"))
        assertTrue("splice upgrade" in streamed.body() && streamed.body().trimEnd().endsWith("data: [DONE]"))
        assertTrue(box.asked.isEmpty(), "an older client's completion reached the provider")
        val current = rig.ask("/v1/chat/completions", key, CHAT, listOf(SPLICE_CLIENT_HEADER to MINIMUM))
        assertEquals("from box", chatText(current))
    }

    @Test
    fun `a launcher's own header, a 426 and the operator's words refuse an older laptop on every route`() {
        val client = ServerClient(
            MINIMUM,
            versionHeader = LAUNCHER_HEADER,
            outdatedStatus = 426,
            outdatedMessage = "Run team-update: {version} is older than {minimum}.",
        )
        val rig = serve { it.copy(client = client) }
        val key = rig.keyFor("ola@example.com")
        val old = listOf(LAUNCHER_HEADER to "team-launcher/0.9.3")
        val words = "splice server: Run team-update: 0.9.3 is older than $MINIMUM."
        val chat = rig.ask("/v1/chat/completions", key, CHAT, old)
        assertEquals(426, chat.statusCode(), chat.body())
        assertEquals(words, openAiError(chat.body()).getValue("message").jsonPrimitive.content)
        val turn = rig.ask("/claude-team/v1/messages", key, TURN, old)
        assertEquals(426, turn.statusCode(), turn.body())
        assertTrue(words in turn.body(), turn.body())
        assertTrue(box.asked.isEmpty())
        val other = listOf(SPLICE_CLIENT_HEADER to "0.1.0")
        assertEquals(200, rig.ask("/v1/chat/completions", key, CHAT, other).statusCode(), "another header is not read")
        val current = listOf(LAUNCHER_HEADER to "team-launcher/$MINIMUM")
        assertEquals("from the head", text(rig.ask("/claude-team/v1/messages", key, TURN, current)))
    }

    @Test
    fun `a check-in stands for a laptop whose turns name no version, for that person alone`() {
        val rig = serve { it.copy(client = ServerClient(MINIMUM, checkin = true, outdatedStatus = 400)) }
        val ola = rig.keyFor("ola@example.com")
        val ann = rig.keyFor("ann@example.com")
        assertEquals(200, rig.ask("/v1/chat/completions", ola, CHAT).statusCode(), "no version and no check-in")
        val checked = rig.ask("/server/client/checkin", ola, """{"version":"0.9.0"}""")
        assertEquals(200, checked.statusCode(), checked.body())
        val answer = Json.parseToJsonElement(checked.body()).jsonObject
        assertEquals("0.9.0", answer.getValue("version").jsonPrimitive.content)
        assertEquals("false", answer.getValue("served").jsonPrimitive.content)
        val refused = rig.ask("/v1/chat/completions", ola, CHAT)
        assertEquals(400, refused.statusCode(), refused.body())
        assertTrue("splice upgrade" in refused.body(), refused.body())
        assertEquals(200, rig.ask("/v1/chat/completions", ann, CHAT).statusCode(), "ann never checked in")
        val sent = rig.ask("/v1/chat/completions", ola, CHAT, listOf(SPLICE_CLIENT_HEADER to MINIMUM))
        assertEquals(200, sent.statusCode(), "a version the turn names wins over the check-in")
        rig.ask("/server/client/checkin", ola, """{"version":"$MINIMUM"}""")
        assertEquals(200, rig.ask("/v1/chat/completions", ola, CHAT).statusCode(), "the latest check-in stands")
        assertEquals(400, rig.ask("/server/client/checkin", ola, """{"version":"new"}""").statusCode())
    }

    @Test
    fun `a check-in past its cap is refused 413, and an overlong or unquoted version 400, keeping nothing`() {
        val rig = serve { it.copy(client = ServerClient(MINIMUM, checkin = true, outdatedStatus = 400)) }
        val key = rig.keyFor("ola@example.com")
        val huge = """{"version":"0.9.0","pad":"${"x".repeat(8 * 1024)}"}"""
        val tooLarge = rig.ask("/server/client/checkin", key, huge)
        assertEquals(413, tooLarge.statusCode(), tooLarge.body())
        val longNumber = """{"version":"0.${"9".repeat(40)}"}"""
        assertEquals(400, rig.ask("/server/client/checkin", key, longNumber).statusCode())
        val manyParts = """{"version":"${List(60) { "1" }.joinToString(".")}"}"""
        assertEquals(400, rig.ask("/server/client/checkin", key, manyParts).statusCode())
        for (unquoted in listOf("""{"version":0.9.3}""", """{"version":0.9}""", """{version:"0.9.3"}""")) {
            assertEquals(400, rig.ask("/server/client/checkin", key, unquoted).statusCode(), unquoted)
        }
        assertEquals(200, rig.ask("/v1/chat/completions", key, CHAT).statusCode(), "no refused check-in was kept")
    }

    // #791: a check-in is the one-key object `{"version": "..."}` written as strict JSON; anything else is refused 400 and
    // replaces nothing, though kotlinx alone would read an unquoted word beside the version as a value.
    @Test
    fun `a check-in with a malformed, extra or mistyped field is refused 400 and keeps the version already held`() {
        val rig = serve { it.copy(client = ServerClient(MINIMUM, checkin = true, outdatedStatus = 400)) }
        val key = rig.keyFor("ola@example.com")
        assertEquals(200, rig.ask("/server/client/checkin", key, """{"version":"$MINIMUM"}""").statusCode())
        val refused = listOf(
            """{"version":"0.9.3","extra":invalid}""",
            """{"version":"0.9.3","extra":[tru]}""",
            """{"version":"0.9.3","extra":01}""",
            """{"version":"0.9.3","extra":"x"}""",
            """{"version":"0.9.3"} trailing""",
            """{"version":"0.9.3",}""",
            """{"version":null}""",
            """{"version":["0.9.3"]}""",
            """{"version":""}""",
            """["0.9.3"]""",
            "\"0.9.3\"",
            "",
        )
        for (body in refused) {
            val answer = rig.ask("/server/client/checkin", key, body)
            assertEquals(400, answer.statusCode(), "$body: ${answer.body()}")
        }
        val turn = rig.ask("/v1/chat/completions", key, CHAT)
        assertEquals(200, turn.statusCode(), "the version checked in first still stands: ${turn.body()}")
    }

    // The operator's words reach the head in a header, which carries visible ASCII alone; the notice is encoded on the
    // way and the head reads it back as written.
    @Test
    fun `a notice in Portuguese is the answer to an older laptop's Messages turn at the alias and at its own path`() {
        val words = "Atualize o cliente — versão {version}; mínimo {minimum}."
        val rig = serve { it.copy(client = ServerClient(MINIMUM, outdatedMessage = words)) }
        val key = rig.keyFor("ola@example.com")
        val old = listOf(SPLICE_CLIENT_HEADER to "0.9.3", "anthropic-version" to "1")
        val expected = "Atualize o cliente — versão 0.9.3; mínimo $MINIMUM."
        for (path in listOf("/v1/messages", "/claude-team/v1/messages")) {
            val turn = rig.ask(path, key, TURN, old)
            assertEquals(200, turn.statusCode(), turn.body())
            assertEquals(expected, text(turn))
        }
        val chat = rig.ask("/v1/chat/completions", key, CHAT, old)
        assertEquals(expected, chatText(chat))
        assertTrue(box.asked.isEmpty())
    }

    @Test
    fun `without checkin set the check-in path is no route`() {
        val rig = serve { it.copy(client = ServerClient(MINIMUM)) }
        val key = rig.keyFor("ola@example.com")
        assertEquals(404, rig.ask("/server/client/checkin", key, """{"version":"0.9.0"}""").statusCode())
        assertTrue(box.asked.isEmpty())
    }

    @Test
    fun `an alias that stands for the door reaches the team's door, not a command`() {
        val rig = serve { it.copy(aliases = mapOf("/v1" to "door")) }
        val key = rig.keyFor("ola@example.com")
        // The rig opens no gateway, so the door answers that it is shut: the path reached the door.
        val turn = rig.ask("/v1/messages", key, TURN, listOf("anthropic-version" to "1"))
        assertEquals(404, turn.statusCode(), turn.body())
        assertTrue("door is not open" in turn.body(), turn.body())
        assertTrue(box.asked.isEmpty())
    }
}

private const val MINIMUM = "0.10.0"
private const val LAUNCHER_HEADER = "x-team-launcher"

// why: ktoml hands a quoted key back with its quotes, so the alias is written as splice.toml has it.
private const val V1_ALIAS = "\"/v1\""
private const val TURN = """{"model":"m","messages":[]}"""
