package splice.provider.antigravity.hooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.client.ForegroundToolCall
import splice.core.client.ForegroundToolPhase
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.provider.antigravity.DesktopAcp
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path

class DesktopAcpHooksTest {
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
    private val opened = "$hello\nread l; echo '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":$session}'"

    @Test
    fun `slash login in an ACP session opens Console Accounts and never reaches agy`() {
        var loginCount = 0
        val hooks = AcpSessionHooks(
            login = AcpLoginOpener {
                loginCount++
                "Opened Splice Console's Accounts page."
            },
        )
        val script = "$opened\nread l && echo 'SHOULD_NOT_RECEIVE_PROMPT' >&2"
        val loginMsg = """{"type":"user","message":{"role":"user","content":"/login"},""" +
            """"parent_tool_use_id":null,"priority":"next","uuid":"u-login"}"""
        val seen = rig(script, hooks) {
            send(loginMsg)
            next { it.str("type") == "result" }
        }
        assertEquals(1, loginCount)
        val reply = seen.single { it.str("type") == "assistant" }.obj("message").array("content").single().jsonObject
        assertEquals("Opened Splice Console's Accounts page.", reply.str("text"))
        assertEquals("false", seen.last()["is_error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `slash login sent during an active ACP turn does not mix into the live answer`() {
        var loginCount = 0
        val hooks = AcpSessionHooks(
            login = AcpLoginOpener {
                loginCount++
                "SHOULD_NOT_MIX"
            },
        )
        val prompt = listOf(
            "read l",
            update(CHUNK.replace("TEXT", "Working")),
            "sleep 0.5",
            answer(3, "end_turn"),
            "read l",
        ).joinToString("\n")
        val loginMsg = """{"type":"user","message":{"role":"user","content":"/login"},""" +
            """"parent_tool_use_id":null,"priority":"next","uuid":"u-login-mid"}"""
        val seen = rig("$opened\n$prompt", hooks) {
            send(USER)
            next { it.str("type") == "stream_event" && it.obj("event").str("type") == "content_block_delta" }
            send(loginMsg)
            next { it.str("type") == "result" }
        }
        assertEquals(0, loginCount, "/login must be ignored while a turn is actively running")
        val reply = seen.single { it.str("type") == "assistant" }.obj("message").array("content").single().jsonObject
        assertEquals("Working", reply.str("text"))
    }

    @Test
    fun `ACP session emits SessionStarts and ForegroundTools lifecycle events in-process`() {
        val starts = mutableListOf<Pair<String, String>>()
        val fg = mutableListOf<ForegroundToolCall>()
        val hooks = AcpSessionHooks(
            sessionStart = AcpSessionStart { id, cwd -> starts += id to cwd },
            foreground = { fg += it },
        )
        val prompt = listOf(
            "read l",
            update(
                """{"sessionUpdate":"tool_call","toolCallId":"t-fg","title":"Run ls","kind":"execute",""" +
                    """"status":"pending","rawInput":{"command":"ls"}}""",
            ),
            update(
                """{"sessionUpdate":"tool_call_update","toolCallId":"t-fg","status":"completed",""" +
                    """"content":[{"type":"content","content":{"type":"text","text":"ok"}}]}""",
            ),
            answer(3, "end_turn"),
            "read l",
        ).joinToString("\n")
        rig("$opened\n$prompt", hooks) {
            send(USER)
            next { it.str("type") == "result" }
        }
        assertEquals(listOf("s-1" to project().toString()), starts)
        assertEquals(
            listOf(
                ForegroundToolPhase.SESSION_START,
                ForegroundToolPhase.START,
                ForegroundToolPhase.END,
                ForegroundToolPhase.SESSION_END,
            ),
            fg.map { it.phase },
        )
        assertEquals("t-fg", fg[1].toolUseId)
        assertEquals("t-fg", fg[2].toolUseId)
    }

    @Test
    fun `resumed ACP session still refreshes ForegroundTools SESSION_START without firing SessionStarts`() {
        val starts = mutableListOf<Pair<String, String>>()
        val fg = mutableListOf<ForegroundToolCall>()
        val hooks = AcpSessionHooks(
            sessionStart = AcpSessionStart { id, cwd -> starts += id to cwd },
            foreground = { fg += it },
            foregroundOwner = "owner-resumed",
        )
        val prompt = listOf("read l", answer(3, "end_turn"), "read l").joinToString("\n")
        rig("$opened\n$prompt", hooks, extraArgs = listOf("--resume=s-1")) {
            send(USER)
            next { it.str("type") == "result" }
        }
        assertEquals(emptyList<Pair<String, String>>(), starts, "resumed ACP session must not re-run SessionStarts")
        assertEquals(listOf(ForegroundToolPhase.SESSION_START, ForegroundToolPhase.SESSION_END), fg.map { it.phase })
        assertEquals("owner-resumed", fg.first().owner)
    }

    private fun rig(
        script: String,
        hooks: AcpSessionHooks,
        extraArgs: List<String> = emptyList(),
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
        ) + extraArgs
        val env = EnvReader { name -> if (name == "HOME") tmp.resolve("home").toString() else null }
        val driver = Driver()
        runBlocking {
            withTimeout(TEST_DEADLINE_MS) {
                val run = async(Dispatchers.IO) {
                    val agy = listOf("sh", agent.toString())
                    DesktopAcp(args, project(), env, Dispatchers.IO, LogSink {}, WallClock { CLOCK }, agy)
                        .run(driver.stdin, driver.stdout, hooks)
                }
                driver.drive()
                driver.close()
                assertEquals(0, run.await())
            }
        }
        return driver.seen
    }

    private fun project(): Path = tmp.resolve("work.dir")

    private fun update(update: String): String =
        """echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s-1","update":$update}}'"""

    private fun answer(id: Int, stop: String): String =
        """echo '{"jsonrpc":"2.0","id":$id,"result":{"stopReason":"$stop"}}'"""

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

        suspend fun next(wanted: (JsonObject) -> Boolean): List<JsonObject> {
            while (true) {
                val line = received.receive()
                seen += line
                if (wanted(line)) return seen
            }
        }

        fun close() = toSession.close()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
}

private const val CHUNK = """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"TEXT"}}"""
private const val USER = """{"type":"user","message":{"role":"user","content":"hi"},""" +
    """"parent_tool_use_id":null,"priority":"next","uuid":"u-1"}"""
private const val TEST_DEADLINE_MS = 30_000L
private const val PIPE_BYTES = 1 shl 16
private const val CLOCK = 1_795_300_000_000L
