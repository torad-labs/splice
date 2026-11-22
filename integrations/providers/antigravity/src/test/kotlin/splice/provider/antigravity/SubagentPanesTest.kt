// Subagent delegation on agy: `invoke_subagent` renders as a Claude `Task` card with `system/task_started` and
// `system/task_notification` frames so Claude Desktop opens and closes subagent panes, including when agy finishes a
// subagent after the turn's `result`, when the user stops the turn, or when agy exits between turns.
package splice.provider.antigravity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.client.ClaudeProjectFolder
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path

internal class SubagentPanesTest {
    @TempDir
    lateinit var tmp: Path

    private val opened = listOf(
        """read l; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'""",
        """read l; echo '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s-1","modes":{"currentModeId":"default"},""" +
            """"configOptions":[{"id":"model","currentValue":"gemini-4-neon",""" +
            """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]}],""" +
            """"models":{"availableModels":[{"modelId":"gemini-4-neon-high"}]}}}'""",
    ).joinToString("\n")

    @Test
    fun `two subagents open two Task panes and close them when agy completes them`() {
        val script = listOf(
            opened,
            "read l",
            delegated(CALL_A, PROMPT_A, ROLE_A),
            delegated(CALL_B, PROMPT_B, ROLE_B),
            upd("""{"sessionUpdate":"tool_call_update","toolCallId":"$CALL_A","status":"completed"}"""),
            upd(
                """{"sessionUpdate":"tool_call_update","toolCallId":"$CALL_B","status":"completed",""" +
                    """"content":[{"type":"content","content":{"type":"text","text":"b.py: clean"}}]}""",
            ),
            upd("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Done."}}"""),
            answer(PROMPT_ID, "end_turn"),
            "read l",
        ).joinToString("\n")
        val seen = runSession(script) {
            send(USER_LINE)
            next { it.str("type") == "result" }
        }
        val cards = seen.filter { it.str("type") == "assistant" }.flatMap { it.blocks() }
            .filter { it.str("type") == "tool_use" }
        assertEquals(listOf("Task", "Task"), cards.map { it.str("name") })
        assertEquals(taskInput(ROLE_A, PROMPT_A), cards[0].obj("input"))
        assertEquals(taskInput(ROLE_B, PROMPT_B), cards[1].obj("input"))
        val started = seen.filter { it.str("type") == "system" && it.str("subtype") == "task_started" }
        assertEquals(listOf(CALL_A, CALL_B), started.map { it.str("task_id") })
        assertEquals(listOf(ROLE_A, ROLE_B), started.map { it.str("description") })
        started.forEach {
            assertEquals("local_agent", it.str("task_type"))
            assertEquals(JsonPrimitive(false), it["is_backgrounded"])
        }
        val ended = seen.filter { it.str("type") == "system" && it.str("subtype") == "task_notification" }
        assertEquals(listOf("completed", "completed"), ended.map { it.str("status") })
        assertEquals(listOf("", "b.py: clean"), ended.map { it.str("summary") })
        seen.forEach { assertFalse("invoke_subagent" in it.toString()) }
    }

    @Test
    fun `a failed subagent closes as failed and an interrupted running one closes as stopped`() {
        val script = listOf(
            opened,
            "read l",
            delegated(CALL_A, PROMPT_A, ROLE_A),
            delegated(CALL_B, PROMPT_B, ROLE_B),
            upd(
                """{"sessionUpdate":"tool_call_update","toolCallId":"$CALL_A","status":"failed",""" +
                    """"content":[{"type":"content","content":{"type":"text","text":"crashed"}}]}""",
            ),
            "read l",
            answer(PROMPT_ID, "cancelled"),
            "read l",
        ).joinToString("\n")
        val seen = runSession(script) {
            send(USER_LINE)
            next { it.str("type") == "system" && it.str("subtype") == "task_notification" }
            send("""{"type":"control_request","request_id":"stop-1","request":{"subtype":"interrupt"}}""")
            next { it.str("type") == "result" }
        }
        val ended = seen.filter { it.str("type") == "system" && it.str("subtype") == "task_notification" }
        assertEquals(listOf(CALL_A, CALL_B), ended.map { it.str("task_id") })
        assertEquals(listOf("failed", "stopped"), ended.map { it.str("status") })
        assertEquals("crashed", ended[0].str("summary"))
        val results = seen.filter { it.str("type") == "user" }.flatMap { it.blocks() }
            .filter { it.str("type") == "tool_result" }
        assertEquals(listOf(CALL_A, CALL_B), results.map { it.str("tool_use_id") })
        assertTrue(results[1].str("content").isNotEmpty(), "cancelled is_error tool_result keeps non-empty content")
        val kept = Files.readAllLines(transcript("s-1")).map { Json.parseToJsonElement(it).jsonObject }
        val keptIds = kept.filter { it.str("type") == "user" }.flatMap { it.blocks() }
            .filter { it.str("type") == "tool_result" }.map { it.str("tool_use_id") }
        assertEquals(listOf(CALL_A, CALL_B), keptIds)
    }

    @Test
    fun `a subagent completing after the turn result still emits its task_notification alone`() {
        val script = listOf(
            opened,
            "read l",
            delegated(CALL_A, PROMPT_A, ROLE_A),
            upd("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Started."}}"""),
            answer(PROMPT_ID, "end_turn"),
            upd(
                """{"sessionUpdate":"tool_call_update","toolCallId":"$CALL_A","status":"completed",""" +
                    """"content":[{"type":"content","content":{"type":"text","text":"audit ok"}}]}""",
            ),
            "read l",
        ).joinToString("\n")
        val seen = runSession(script) {
            send(USER_LINE)
            next { it.str("type") == "result" }
            next { it.str("type") == "system" && it.str("subtype") == "task_notification" }
        }
        val resultIdx = seen.indexOfFirst { it.str("type") == "result" }
        val after = seen.drop(resultIdx + 1)
        assertEquals(1, after.size)
        assertEquals("task_notification", after.single().str("subtype"))
        assertEquals(CALL_A, after.single().str("task_id"))
        assertEquals("completed", after.single().str("status"))
        assertEquals("audit ok", after.single().str("summary"))
    }

    @Test
    fun `agy exiting between turns closes any still-running post-turn subagent pane as stopped`() {
        val script = listOf(
            opened,
            "read l",
            delegated(CALL_A, PROMPT_A, ROLE_A),
            upd("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Started."}}"""),
            answer(PROMPT_ID, "end_turn"),
        ).joinToString("\n")
        val seen = runSession(script) {
            send(USER_LINE)
            next { it.str("type") == "result" }
            next { it.str("type") == "system" && it.str("subtype") == "task_notification" }
        }
        val ended = seen.single { it.str("type") == "system" && it.str("subtype") == "task_notification" }
        assertEquals(CALL_A, ended.str("task_id"))
        assertEquals("stopped", ended.str("status"))
    }

    private fun runSession(script: String, drive: suspend Driver.() -> Unit): List<JsonObject> {
        val agent = tmp.resolve("fake-agy.sh")
        Files.writeString(agent, script)
        Files.createDirectories(project())
        val args = listOf(
            "--output-format",
            "stream-json",
            "--model=claude-antigravity--gem-4-neon",
            "--permission-mode=default",
            "--stands-in-for=/home/me/.config/Claude/claude-code/2.1.328/10f24c9cfcbf/claude",
        )
        val env = EnvReader { name -> if (name == "HOME") home().toString() else null }
        val driver = Driver()
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val run = async(Dispatchers.IO) {
                    val agy = listOf("sh", agent.toString())
                    DesktopAcp(args, project(), env, Dispatchers.IO, LogSink {}, WallClock { CLOCK_MS }, agy)
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
        .resolve(ClaudeProjectFolder.of(project().toString())).resolve("$id.jsonl")

    private fun delegated(id: String, prompt: String, role: String): String = upd(
        """{"sessionUpdate":"tool_call","toolCallId":"$id","title":"invoke_subagent","name":"invoke_subagent",""" +
            """"status":"in_progress","rawInput":{"Prompt":"$prompt","Role":"$role","TypeName":"research"}}""",
    )

    private fun upd(update: String): String =
        """echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-1","update":$update}}'"""

    private fun answer(id: Int, stop: String): String =
        """echo '{"jsonrpc":"2.0","id":$id,"result":{"stopReason":"$stop"}}'"""

    private fun taskInput(role: String, prompt: String): JsonObject = buildJsonObject {
        put("description", role)
        put("prompt", prompt)
        put("subagent_type", "research")
    }

    private class Driver {
        private val toSession = PipedOutputStream()
        val stdin = PipedInputStream(toSession, PIPE_SIZE)
        private val received = Channel<JsonObject>(Channel.UNLIMITED)
        val seen = mutableListOf<JsonObject>()
        val stdout: OutputStream = object : OutputStream() {
            private val buf = ByteArrayOutputStream()

            override fun write(b: Int) {
                if (b != '\n'.code) {
                    buf.write(b)
                    return
                }
                val line = buf.toString(Charsets.UTF_8).trim()
                buf.reset()
                if (line.isNotEmpty()) received.trySend(Json.parseToJsonElement(line).jsonObject)
            }
        }

        fun send(line: String) {
            toSession.write((line + "\n").toByteArray(Charsets.UTF_8))
            toSession.flush()
        }

        suspend fun next(match: (JsonObject) -> Boolean): JsonObject {
            while (true) {
                val line = received.receive()
                seen += line
                if (match(line)) return line
            }
        }

        fun close() = toSession.close()
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.blocks(): List<JsonObject> =
        ((obj("message")["content"] as? JsonArray)?.map { it.jsonObject }).orEmpty()
}

private const val TIMEOUT_MS = 15_000L
private const val CLOCK_MS = 1_795_300_000_000L
private const val PIPE_SIZE = 1 shl 16
private const val PROMPT_ID = 3
private const val CALL_A = "call_sub_1"
private const val CALL_B = "call_sub_2"
private const val ROLE_A = "a.py Auditor"
private const val ROLE_B = "b.py Auditor"
private const val PROMPT_A = "Audit a.py"
private const val PROMPT_B = "Audit b.py"
private const val USER_LINE =
    """{"type":"user","uuid":"u-1","message":{"role":"user","content":"audit with subagents"}}"""
