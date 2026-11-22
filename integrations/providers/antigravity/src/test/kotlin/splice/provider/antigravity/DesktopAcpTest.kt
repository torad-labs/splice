// What Claude Desktop sees from a session that runs on agy, driven with the lines Desktop writes against a scripted
// agent that answers as `agy acp` does: the answer to `initialize`, a streamed turn, a tool card and its result, a
// permission prompt answered in Desktop, Stop, a signed-out agy, a session taken up that agy does not know, and the
// transcript and session record a `claude` keeps.
package splice.provider.antigravity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path

internal class DesktopAcpTest {
    @TempDir
    lateinit var tmp: Path

    private val session =
        """{"sessionId":"s-1","modes":{"currentModeId":"default"},"configOptions":[""" +
            """{"id":"model","currentValue":"gemini-4-neon",""" +
            """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]},""" +
            """{"id":"thought_level","currentValue":"high","options":[{"value":"low","name":"Low"},""" +
            """{"value":"high","name":"High"}]}],""" +
            """"models":{"availableModels":[{"modelId":"gemini-4-neon-low"},{"modelId":"gemini-4-neon-high"}]}}"""

    private val hello = """read l; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'"""

    /** agy's commands, written before it answers `session/new`. */
    private val commands = update(
        """{"sessionUpdate":"available_commands_update",""" +
            """"availableCommands":[{"name":"goal","description":"Set a goal","input":{"hint":"<goal>"}}]}""",
    )

    private val opened = "$hello\nread l; $commands; echo '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":$session}'"

    @Test
    fun `initialize is answered with agy's models under Desktop's names and its commands`() {
        val seen = rig(opened + "\nread l") {
            send("""{"type":"control_request","request_id":"init-1","request":{"subtype":"initialize"}}""")
            next { it.str("type") == "control_response" }
        }
        val answer = seen.last().obj("response")
        assertEquals("success", answer.str("subtype"))
        assertEquals("init-1", answer.str("request_id"))
        val body = answer.obj("response")
        assertEquals(listOf("claude-antigravity--gem-4-neon"), body.array("models").map { it.jsonObject.str("value") })
        assertEquals(listOf("goal"), body.array("commands").map { it.jsonObject.str("name") })
        assertEquals(0, body.array("agents").size)
        assertEquals("false", body["mcp_servers_applied"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a turn streams its thinking and text, ends with a result, and is kept in the transcript`() {
        val prompt = lines(
            "read l",
            update(THOUGHT.replace("TEXT", "Let me think")),
            update(CHUNK.replace("TEXT", "Hello")),
            update(CHUNK.replace("TEXT", " there")),
            answer(3, "end_turn"),
            "read l",
        )
        var record: String? = null
        val seen = rig("$opened\n$prompt") {
            send(USER)
            next { it.str("type") == "result" }.also { record = Files.readString(recordFile()) }
        }
        val replay = seen.first { it.str("type") == "user" }
        assertEquals("true", replay["isReplay"]?.jsonPrimitive?.content)
        assertEquals("u-1", replay.str("uuid"))
        val init = seen.first { it.str("subtype") == "init" }
        assertEquals("s-1", init.str("session_id"))
        assertEquals("claude-antigravity--gem-4-neon", init.str("model"))
        assertEquals("2.1.328", init.str("claude_code_version"))
        assertTrue("goal" in init.array("slash_commands").map { it.jsonPrimitive.content })
        val deltas = seen.filter { it.str("type") == "stream_event" }.map { it.obj("event") }
            .filter { it.str("type") == "content_block_delta" }.map { it.obj("delta").str("type") }
        assertEquals(listOf("thinking_delta", "text_delta", "text_delta"), deltas)
        val message = seen.single { it.str("type") == "assistant" }.obj("message")
        assertEquals(listOf("thinking", "text"), message.array("content").map { it.jsonObject.str("type") })
        assertEquals("Hello there", message.array("content")[1].jsonObject.str("text"))
        val result = seen.last()
        assertEquals("success", result.str("subtype"))
        assertEquals("false", result["is_error"]?.jsonPrimitive?.content)
        assertEquals("end_turn", result.str("stop_reason"))
        assertEquals("s-1", result.str("session_id"))

        val lines = Files.readAllLines(transcript("s-1")).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("user", "assistant"), lines.map { it.str("type") })
        assertNull(lines[0].str("parentUuid"))
        assertEquals("u-1", lines[1].str("parentUuid"))
        assertEquals("Hello there", lines[1].obj("message").array("content")[1].jsonObject.str("text"))
        val running = Json.parseToJsonElement(checkNotNull(record)).jsonObject
        assertEquals("s-1", running.str("sessionId"))
        assertEquals(project().toString(), running.str("cwd"))
        assertFalse(Files.exists(recordFile()), "the session record is removed when the session ends")
    }

    @Test
    fun `a tool call shows as a Claude tool card followed by its result`() {
        val prompt = lines(
            "read l",
            update(
                """{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Run ls","kind":"execute",""" +
                    """"status":"pending","rawInput":{"command":"ls -la"}}""",
            ),
            update(
                """{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed",""" +
                    """"content":[{"type":"content","content":{"type":"text","text":"file.txt"}}]}""",
            ),
            update(CHUNK.replace("TEXT", "Done")),
            answer(3, "end_turn"),
            "read l",
        )
        val seen = rig("$opened\n$prompt") {
            send(USER)
            next { it.str("type") == "result" }
        }
        val turn = seen.filter { it.str("type") == "assistant" || it.str("type") == "user" }.drop(1)
        val card = turn[0].obj("message").array("content").single().jsonObject
        assertEquals("tool_use", card.str("type"))
        assertEquals("Bash", card.str("name"))
        assertEquals("ls -la", card.obj("input").str("command"))
        val result = turn[1].obj("message").array("content").single().jsonObject
        assertEquals("tool_result", result.str("type"))
        assertEquals("t1", result.str("tool_use_id"))
        assertEquals("file.txt", result.str("content"))
        assertEquals("Done", turn[2].obj("message").array("content").single().jsonObject.str("text"))
    }

    @Test
    fun `a permission agy asks for is Desktop's prompt, and Desktop's answer reaches agy`() {
        val asks = """echo '{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{""" +
            """"sessionId":"s-1","toolCall":{"toolCallId":"t2","title":"Edit a.txt","kind":"edit","content":[""" +
            """{"type":"diff","path":"/p/a.txt","oldText":"a","newText":"b"}]},"options":[{"optionId":"yes",""" +
            """"name":"Allow","kind":"allow_once"},{"optionId":"no","name":"Reject","kind":"reject_once"}]}}'"""
        val prompt = lines(
            "read l",
            asks,
            "read l",
            """case "${'$'}l" in *'"optionId":"yes"'*) said=allowed ;; *) said=refused ;; esac""",
            update(CHUNK.replace("TEXT", "'${'$'}said'")),
            answer(3, "end_turn"),
            "read l",
        )
        val seen = rig("$opened\n$prompt") {
            send(USER)
            val asked = next { it.str("type") == "control_request" }.last()
            val request = asked.obj("request")
            assertEquals("can_use_tool", request.str("subtype"))
            assertEquals("Edit", request.str("tool_name"))
            assertEquals("t2", request.str("tool_use_id"))
            assertEquals("/p/a.txt", request.obj("input").str("file_path"))
            assertEquals("a", request.obj("input").str("old_string"))
            assertEquals("b", request.obj("input").str("new_string"))
            send(
                """{"type":"control_response","response":{"subtype":"success",""" +
                    """"request_id":"${asked.str("request_id")}","response":{"behavior":"allow","updatedInput":{}}}}""",
            )
            next { it.str("type") == "result" }
        }
        val texts = seen.filter { it.str("type") == "assistant" }.flatMap { it.obj("message").array("content") }
            .map { it.jsonObject }.filter { it.str("type") == "text" }.map { it.str("text") }
        assertEquals(listOf("allowed"), texts)
        val cardAt = seen.indexOfFirst { it.str("type") == "assistant" }
        assertTrue(cardAt in 0 until seen.indexOfFirst { it.str("type") == "control_request" })
    }

    @Test
    fun `Stop cancels agy's turn, and the turn ends as cancelled`() {
        val prompt = lines(
            "read l",
            update(CHUNK.replace("TEXT", "Working")),
            "read l",
            """case "${'$'}l" in *session/cancel*) """ + answer(3, "cancelled") + " ;; esac",
            "read l",
        )
        val seen = rig("$opened\n$prompt") {
            send(USER)
            next { it.str("type") == "stream_event" && it.obj("event").str("type") == "content_block_delta" }
            send("""{"type":"control_request","request_id":"stop-1","request":{"subtype":"interrupt"}}""")
            next { it.str("type") == "result" }
        }
        val stopped = seen.single { it.str("type") == "control_response" }.obj("response")
        assertEquals("stop-1", stopped.str("request_id"))
        assertEquals("success", stopped.str("subtype"))
        assertEquals("cancelled", seen.last().str("stop_reason"))
        val said = seen.single { it.str("type") == "assistant" }.obj("message").array("content").single()
        assertEquals("Working", said.jsonObject.str("text"))
    }

    @Test
    fun `a signed-out agy starts the session, and each turn says how to sign in`() {
        val signedOut = lines(
            hello,
            """read l; echo '{"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"Authentication required"}}'""",
            "read l",
        )
        val seen = rig(signedOut) {
            send("""{"type":"control_request","request_id":"init-1","request":{"subtype":"initialize"}}""")
            next { it.str("type") == "control_response" }
            send(USER)
            next { it.str("type") == "result" }
        }
        assertEquals("success", seen.first { it.str("type") == "control_response" }.obj("response").str("subtype"))
        val said = seen.single { it.str("type") == "assistant" }.obj("message").array("content").single().jsonObject
        assertTrue("agy acp --login" in said.str("text").orEmpty(), said.toString())
        assertEquals("true", seen.last()["is_error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a session agy does not know is taken up under Desktop's id on a new agy session`() {
        val script = lines(
            hello,
            """read l; echo '{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"unknown session"}}'""",
            """read l; echo '{"jsonrpc":"2.0","id":3,"result":$session}'""",
            "read l",
            """case "${'$'}l" in *'"sessionId":"s-1"'*) said=prompted ;; *) said=lost ;; esac""",
            update(CHUNK.replace("TEXT", "'${'$'}said'")),
            answer(4, "end_turn"),
            "read l",
        )
        val claudeSession = "9d7c1d2e-0000-4000-8000-000000000001"
        Files.createDirectories(transcript(claudeSession).parent)
        Files.writeString(transcript(claudeSession), """{"type":"user","uuid":"from-claude","sessionId":"x"}""" + "\n")
        val seen = rig(script, listOf("--resume=$claudeSession")) {
            send(USER)
            next { it.str("type") == "result" }
        }
        assertEquals(claudeSession, seen.first { it.str("subtype") == "init" }.str("session_id"))
        val text = seen.single { it.str("type") == "assistant" }.obj("message").array("content").single()
        assertEquals("prompted", text.jsonObject.str("text"))
        val lines = Files.readAllLines(transcript(claudeSession)).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("from-claude", lines[1].str("parentUuid"), "Gemini's turns follow the conversation so far")
    }

    /** Desktop's session on the agent [script], driven by [drive]; every line Desktop received, in order. */
    private fun rig(
        script: String,
        extra: List<String> = emptyList(),
        drive: suspend Driver.() -> Unit,
    ): List<JsonObject> {
        val agent = tmp.resolve("agent.sh")
        Files.writeString(agent, script)
        Files.createDirectories(project())
        val args = listOf(
            "--output-format",
            "stream-json",
            "--model=claude-antigravity--gem-4-neon",
            "--permission-mode=default",
            "--stands-in-for=/home/me/.config/Claude/claude-code/2.1.328/10f24c9cfcbf/claude",
        ) + extra
        val env = EnvReader { name -> if (name == "HOME") home().toString() else null }
        val driver = Driver()
        runBlocking {
            withTimeout(TEST_DEADLINE_MS) {
                val run = async(Dispatchers.IO) {
                    val agy = listOf("sh", agent.toString())
                    DesktopAcp(args, project(), env, Dispatchers.IO, LogSink {}, WallClock { CLOCK }, agy)
                        .run(driver.stdin, driver.stdout)
                }
                driver.drive()
                driver.close()
                assertEquals(0, run.await())
            }
        }
        return driver.seen
    }

    private fun home(): Path = tmp.resolve("home")

    private fun project(): Path = tmp.resolve("work.dir")

    private fun transcript(id: String): Path = home().resolve(".claude").resolve("projects")
        .resolve(ProjectFolder().of(project().toString())).resolve("$id.jsonl")

    private fun recordFile(): Path =
        home().resolve(".claude").resolve("sessions").resolve("${ProcessHandle.current().pid()}.json")

    private fun update(update: String): String =
        """echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-1","update":$update}}'"""

    private fun answer(id: Int, stop: String): String =
        """echo '{"jsonrpc":"2.0","id":$id,"result":{"stopReason":"$stop"}}'"""

    private fun lines(vararg lines: String): String = lines.joinToString("\n")

    /** Desktop's side of the pipes: what it writes, and every line it has received. */
    private class Driver {
        private val toSession = PipedOutputStream()
        val stdin = PipedInputStream(toSession, PIPE_BYTES)
        private val received = Channel<JsonObject>(Channel.UNLIMITED)
        val seen = mutableListOf<JsonObject>()
        val stdout: OutputStream = object : OutputStream() {
            private val line = ByteArrayOutputStream()

            override fun write(b: Int) {
                if (b != '\n'.code) {
                    line.write(b)
                    return
                }
                received.trySend(Json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject)
                line.reset()
            }
        }

        fun send(line: String) {
            toSession.write((line + "\n").toByteArray())
            toSession.flush()
        }

        /** The lines received until one matches [wanted], that one last. */
        suspend fun next(wanted: (JsonObject) -> Boolean): List<JsonObject> {
            while (true) {
                val line = received.receive()
                seen += line
                if (wanted(line)) return seen
            }
        }

        fun close() = toSession.close()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
}

private const val CHUNK = """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"TEXT"}}"""
private const val THOUGHT = """{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"TEXT"}}"""

/** Desktop's first message of a turn. */
private const val USER = """{"type":"user","message":{"role":"user","content":"hi"},""" +
    """"parent_tool_use_id":null,"priority":"next","uuid":"u-1"}"""

/** A whole session against a local script takes well under this; past it the test fails instead of hanging. */
private const val TEST_DEADLINE_MS = 30_000L

private const val PIPE_BYTES = 1 shl 16

private const val CLOCK = 1_795_300_000_000L
