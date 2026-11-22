// Tests for agy-desktop-parity-2: Desktop MCP servers passthrough & mcp_set_servers, loopback SDK MCP bridge,
// Claude config-file MCP discovery, and mid-session live model switching to/from Gemini.
package splice.provider.antigravity

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

internal class AgyMcpAndSwitchTest {
    @TempDir
    lateinit var tmp: Path

    private val http: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `Desktop MCP servers pass to agy, map tool names, track status, and never leak secrets`() {
        val turn = listOf(
            """upd '{"sessionUpdate":"tool_call","toolCallId":"t-m","name":"mcp_files_read_file","kind":"other",""" +
                """"title":"Run mcp_files_read_file","status":"in_progress","rawInput":{"path":"/w/a.txt"}}'""",
            """upd '{"sessionUpdate":"tool_call_update","toolCallId":"t-m","status":"completed",""" +
                """"content":[{"type":"content","content":{"type":"text","text":"hello"}}]}'""",
            """stop "${'$'}id" end_turn""",
        ).joinToString("\n")
        val rig = Rig(tmp)
        var before = JsonArray(emptyList())
        var after = JsonArray(emptyList())
        val seen = rig.run(ScriptedAgy(turns = listOf(turn))) {
            control("init-1", """{"subtype":"initialize","mcpServers":{$FILES,$DOCS,$ODD}}""")
            val ans = answer("init-1")
            assertEquals("success", ans.str("subtype"))
            assertEquals(JsonPrimitive(true), ans.obj("response")["mcp_servers_applied"])
            control("st-1", """{"subtype":"mcp_status"}""")
            before = answer("st-1").obj("response").array("mcpServers")
            send(userMsg("read a.txt", "u-1"))
            result()
            control("st-2", """{"subtype":"mcp_status"}""")
            after = answer("st-2").obj("response").array("mcpServers")
        }
        assertEquals(parseArray("[$FILES_ACP,$DOCS_ACP]"), rig.asked("session/new").single()["mcpServers"])
        assertEquals(listOf("pending", "pending"), before.map { it.jsonObject.str("status") })
        assertEquals(listOf("connected", "pending"), after.map { it.jsonObject.str("status") })
        val card = seen.lines.filter { it.str("type") == "assistant" }
            .flatMap { (it.obj("message")["content"] as? JsonArray).orEmpty().map { b -> b.jsonObject } }
            .single { it.str("type") == "tool_use" }
        assertEquals("mcp__files__read_file", card.str("name"))
        assertTrue("odd" in rig.logged.toString())
        seen.raw.forEach { assertFalse("sekret" in it) }
        assertFalse("sekret" in rig.logged.toString())
    }

    @Test
    fun `mcp_set_servers adds servers and reports dropped servers as errors`() {
        val rig = Rig(tmp)
        var added = JsonArray(emptyList())
        var dropped = JsonObject(emptyMap())
        rig.run(ScriptedAgy(onResume = RESUMED)) {
            control("init-1", """{"subtype":"initialize","mcpServers":{$FILES}}""")
            answer("init-1")
            control("set-1", """{"subtype":"mcp_set_servers","servers":{$FILES,$EXTRA}}""")
            added = answer("set-1").obj("response").array("added")
            control("set-2", """{"subtype":"mcp_set_servers","servers":{$FILES}}""")
            dropped = answer("set-2").obj("response")
        }
        assertEquals(listOf("extra"), added.map { (it as JsonPrimitive).content })
        assertEquals(0, dropped.array("removed").size)
        assertEquals(setOf("extra"), dropped.obj("errors").keys)
        assertEquals(1, rig.asked("session/resume").size)
    }

    @Test
    fun `SDK MCP servers bridge over authenticated loopback HTTP even while session opens`() {
        val connectDuringOpen = listOf(
            """      url=${'$'}(printf '%s\n' "${'$'}l" | grep -o '"url":"http://127.0.0.1:[0-9]*/mcp/ccd_session"'""" +
                """ | head -1 | sed 's/^"url":"//; s/"${'$'}//')""",
            """      tok=${'$'}(printf '%s\n' "${'$'}l" | grep -o 'Bearer [0-9a-f]*' | head -1)""",
            """      curl -s -o "${'$'}DIR/during-open.json" -H 'Content-Type: application/json'""" +
                """ -H "Authorization: ${'$'}tok" --data '$MCP_INIT' "${'$'}url"""",
            OPEN_NEW,
        ).joinToString("\n")
        val rig = Rig(tmp)
        var status = JsonArray(emptyList())
        val seen = rig.run(ScriptedAgy(onNew = connectDuringOpen)) {
            control(
                "init-1",
                """{"subtype":"initialize","mcpServers":{"ccd_session":{"type":"sdk","name":"ccd_session"}},""" +
                    """"sdkMcpServers":["ccd_session","computer-use"]}""",
            )
            val hello = nextMcpMessage()
            assertEquals("ccd_session", hello.obj("request").str("server_name"))
            send(mcpReply(hello.str("request_id").orEmpty(), MCP_INIT_OK))
            assertEquals(JsonPrimitive(true), answer("init-1").obj("response")["mcp_servers_applied"])
            val entries = rig.asked("session/new").single().array("mcpServers").map { it.jsonObject }
            val ccd = entries.first { it.str("name") == "ccd_session" }
            val comp = entries.first { it.str("name") == "computer-use" }
            val tok = ccd.array("headers").single().jsonObject.str("value").orEmpty()
            val ccdUrl = checkNotNull(ccd.str("url"))
            val compUrl = checkNotNull(comp.str("url"))
            coroutineScope {
                val note = async(Dispatchers.IO) { post(compUrl, tok, MCP_NOTIF) }
                val passed = nextMcpMessage()
                assertEquals("computer-use", passed.obj("request").str("server_name"))
                assertEquals(202, note.await().statusCode())
                assertEquals(401, async(Dispatchers.IO) { post(ccdUrl, null, MCP_INIT) }.await().statusCode())
                val badUrl = ccdUrl.substringBeforeLast('/') + "/bad"
                assertEquals(404, async(Dispatchers.IO) { post(badUrl, tok, MCP_INIT) }.await().statusCode())
                assertEquals(405, async(Dispatchers.IO) { get(ccdUrl, tok) }.await().statusCode())
            }
            control("st-1", """{"subtype":"mcp_status"}""")
            status = answer("st-1").obj("response").array("mcpServers")
        }
        assertEquals(listOf("connected", "pending"), status.map { it.jsonObject.str("status") })
        val tok = rig.asked("session/new").single().array("mcpServers")[0].jsonObject
            .array("headers").single().jsonObject.str("value").orEmpty().removePrefix("Bearer ")
        seen.raw.forEach { assertFalse(tok in it) }
    }

    @Test
    fun `Claude config-file MCP servers load with expansion and Desktop overrides collisions`() {
        val rig = Rig(tmp)
        Files.createDirectories(rig.home.resolve(".claude"))
        Files.createDirectories(rig.project.resolve(".claude"))
        Files.writeString(
            rig.home.resolve(".claude.json"),
            """{"mcpServers":{"user-s":{"command":"/opt/u","args":[]},""" +
                """"shared":{"command":"/opt/old","args":[]}}}""",
        )
        Files.writeString(
            rig.project.resolve(".mcp.json"),
            """{"mcpServers":{"repo-ok":{"command":"/opt/r","args":["${'$'}{REPO_ARG:-fallback}"]},""" +
                """"repo-no":{"command":"/opt/no","args":[]}}}""",
        )
        Files.writeString(
            rig.project.resolve(".claude").resolve("settings.local.json"),
            """{"enabledMcpjsonServers":["repo-ok"]}""",
        )
        rig.run(ScriptedAgy()) {
            control(
                "init-1",
                """{"subtype":"initialize","mcpServers":{"shared":{"type":"http","url":"https://win.test/mcp"}}}""",
            )
            answer("init-1")
        }
        val opened = rig.asked("session/new").single().array("mcpServers")
            .map { it.jsonObject }.associateBy { it.str("name") }
        assertEquals(setOf("user-s", "repo-ok", "shared"), opened.keys)
        assertEquals("fallback", checkNotNull(opened["repo-ok"]).array("args")[0].primitiveText())
        assertEquals("https://win.test/mcp", checkNotNull(opened["shared"]).str("url"))
    }

    @Test
    fun `live switch from Claude hands off transcript cleanly and set_model to Claude exits cleanly`() {
        val rig = Rig(tmp)
        val id = "9d7c1d2e-0000-4000-8000-000000000001"
        val tx = rig.transcriptFile(id)
        Files.createDirectories(tx.parent)
        val seed = listOf(
            """{"parentUuid":null,"isSidechain":false,"type":"user",""" +
                """"message":{"role":"user","content":"Check a.py"},"uuid":"c1"}""",
            """{"parentUuid":"c1","isSidechain":false,"type":"assistant","message":{"role":"assistant",""" +
                """"model":"claude-opus-5-5","content":[{"type":"thinking","thinking":"SECRET"},""" +
                """{"type":"text","text":"Looking"},{"type":"tool_use","id":"t1","name":"Bash",""" +
                """"input":{"command":"cat a.py"}}]},"uuid":"c2"}""",
            """{"parentUuid":"c2","isSidechain":false,"type":"user","message":{"role":"user",""" +
                """"content":[{"type":"tool_result","tool_use_id":"t1","content":"RESULT"}]},"uuid":"c3"}""",
        )
        Files.writeString(tx, seed.joinToString("\n") + "\n")
        var exitCode: Int? = null
        var before = 0
        var after = -1
        val seen = rig.run(ScriptedAgy(), extra = listOf("--resume=$id")) {
            control("init-1", """{"subtype":"initialize"}""")
            answer("init-1")
            control("sm-1", """{"subtype":"set_model","model":"claude-antigravity--gem-4-neon"}""")
            answer("sm-1")
            send(userMsg("fix it", "u-9"))
            result()
            before = this.seen.size
            control("sm-2", """{"subtype":"set_model","model":"claude-opus-5-5"}""")
            exitCode = withTimeoutOrNull(10_000L) { running.await() }
            drain()
            after = this.seen.size
        }
        assertEquals(0, exitCode)
        assertEquals(before, after)
        val blocks = rig.asked("session/prompt").single().array("prompt").map { it.jsonObject }
        assertEquals(2, blocks.size)
        val handoff = blocks[0].str("text").orEmpty()
        assertTrue("Check a.py" in handoff && "Looking" in handoff && "Bash" in handoff && "cat a.py" in handoff)
        assertFalse("SECRET" in handoff || "RESULT" in handoff)
        seen.raw.forEach { assertFalse("Check a.py" in it) }
    }

    private fun post(url: String, token: String?, body: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token != null) {
            val header = if (token.startsWith("Bearer ")) token else "Bearer $token"
            req.header("Authorization", header)
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(url: String, token: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(url)).header("Authorization", token).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun mcpReply(id: String, resp: String) =
        """{"type":"control_response","response":{"subtype":"success",""" +
            """"request_id":"$id","response":{"mcp_response":$resp}}}"""

    private class ScriptedAgy(
        val onNew: String = OPEN_NEW,
        val onResume: String = UNKNOWN,
        val turns: List<String> = listOf("""stop "${'$'}id" end_turn"""),
    ) {
        fun script(log: Path): String {
            val cases = turns.mapIndexed { idx, body -> "      ${idx + 1})\n$body\n        ;;" }.joinToString("\n")
            val updFn = """upd() { printf '{"jsonrpc":"2.0","method":"session/update",""" +
                """"params":{"sessionId":"s-1","update":%s}}\n' "${'$'}1"; }"""
            val stopFn = """stop() { printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"%s"}}\n'""" +
                """ "${'$'}1" "${'$'}2"; }"""
            val idSed = """s/^{"jsonrpc":"2.0","id":\([0-9][0-9]*\),"method":.*/\1/p"""
            val mSed = """s/^{"jsonrpc":"2.0",\("id":[0-9][0-9]*,\)\{0,1\}"method":"\([^"]*\)".*/\2/p"""
            val raw =
                """
                |LOG='$log'
                |DIR=${'$'}(dirname "${'$'}LOG")
                |say() { printf '%s\n' "${'$'}1"; }
                |$updFn
                |$stopFn
                |n=0
                |while IFS= read -r l; do
                |  printf '%s\n' "${'$'}l" >> "${'$'}LOG"
                |  id=${'$'}(printf '%s\n' "${'$'}l" | sed -n '$idSed')
                |  m=${'$'}(printf '%s\n' "${'$'}l" | sed -n '$mSed')
                |  case "${'$'}m" in
                |    initialize) say '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":{"protocolVersion":1}}' ;;
                |    session/new)
                |$onNew
                |      ;;
                |    session/resume)
                |$onResume
                |      ;;
                |    session/prompt)
                |      n=${'$'}((n + 1))
                |      case "${'$'}n" in
                |$cases
                |      *) stop "${'$'}id" end_turn ;;
                |      esac
                |      ;;
                |    *) if [ -n "${'$'}id" ]; then say '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":{}}'; fi ;;
                |  esac
                |done
                |"""
            return raw.trimMargin()
        }
    }

    private class Rig(private val tmp: Path) {
        val home: Path = tmp.resolve("home")
        val project: Path = tmp.resolve("work.dir")
        private val agyLog: Path = tmp.resolve("agy.jsonl")
        val logged = StringBuffer()

        fun asked(method: String): List<JsonObject> =
            if (!Files.exists(agyLog)) {
                emptyList()
            } else {
                Files.readAllLines(agyLog).filter(String::isNotBlank)
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .filter { it.str("method") == method }
                    .map { it.obj("params") }
            }

        fun transcriptFile(id: String): Path = home.resolve(".claude").resolve("projects")
            .resolve(ClaudeProjectFolder.of(project.toString())).resolve("$id.jsonl")

        fun run(agy: ScriptedAgy, extra: List<String> = emptyList(), drive: suspend Driver.() -> Unit): SeenOut {
            Files.createDirectories(project)
            Files.createDirectories(home)
            val script = tmp.resolve("agy.sh")
            Files.writeString(script, agy.script(agyLog))
            val args = listOf(
                "--output-format",
                "stream-json",
                "--model=claude-antigravity--gem-4-neon",
                "--permission-mode=default",
                "--stands-in-for=/home/me/claude-code/2.1.328/x/claude",
            ) + extra
            val env = EnvReader { if (it == "HOME") home.toString() else null }
            val driver = Driver()
            return runBlocking {
                withTimeout(30_000L) {
                    val job = async(Dispatchers.IO) {
                        DesktopAcp(
                            args,
                            project,
                            env,
                            Dispatchers.IO,
                            LogSink { logged.append(it) },
                            WallClock { 1_795_300_000_000L },
                            listOf("sh", script.toString()),
                        ).run(driver.stdin, driver.stdout)
                    }
                    driver.running = job
                    driver.drive()
                    driver.close()
                    job.await()
                    driver.drain()
                    SeenOut(driver.seen.toList(), driver.raw.toList())
                }
            }
        }
    }

    private data class SeenOut(val lines: List<JsonObject>, val raw: List<String>)

    private class Driver {
        private val toSession = PipedOutputStream()
        val stdin = PipedInputStream(toSession, 1 shl 16)
        private val ch = Channel<String>(Channel.UNLIMITED)
        val seen = mutableListOf<JsonObject>()
        val raw = mutableListOf<String>()
        lateinit var running: Deferred<Int>
        private var closed = false
        val stdout: OutputStream = object : OutputStream() {
            private val buf = ByteArrayOutputStream()
            override fun write(b: Int) {
                if (b != '\n'.code) {
                    buf.write(b)
                } else {
                    ch.trySend(buf.toString(Charsets.UTF_8))
                    buf.reset()
                }
            }
        }

        fun send(line: String) {
            toSession.write((line + "\n").toByteArray())
            toSession.flush()
        }

        fun control(id: String, req: String) =
            send("""{"type":"control_request","request_id":"$id","request":$req}""")

        suspend fun next(pred: (JsonObject) -> Boolean): JsonObject {
            while (true) {
                val txt = ch.receive()
                raw += txt
                val obj = Json.parseToJsonElement(txt).jsonObject
                seen += obj
                if (pred(obj)) return obj
            }
        }

        suspend fun nextMcpMessage(): JsonObject =
            next { it.str("type") == "control_request" && it.obj("request").str("subtype") == "mcp_message" }

        suspend fun answer(id: String): JsonObject =
            next { it.str("type") == "control_response" && it.obj("response").str("request_id") == id }.obj("response")

        suspend fun result(): JsonObject = next { it.str("type") == "result" }

        fun close() {
            if (!closed) {
                closed = true
                toSession.close()
            }
        }

        fun drain() {
            while (true) {
                val txt = ch.tryReceive().getOrNull() ?: return
                raw += txt
                seen += Json.parseToJsonElement(txt).jsonObject
            }
        }
    }

    private fun userMsg(text: String, uuid: String) =
        """{"type":"user","message":{"role":"user","content":"$text"},"parent_tool_use_id":null,"uuid":"$uuid"}"""

    private fun parseArray(s: String): JsonArray = Json.parseToJsonElement(s).jsonArray
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

private fun JsonElement.primitiveText(): String = (this as JsonPrimitive).content

private const val SESSION_DATA =
    """{"sessionId":"s-1","modes":{"currentModeId":"default"},"configOptions":[""" +
        """{"id":"model","currentValue":"gemini-4-neon",""" +
        """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]}],""" +
        """"models":{"availableModels":[{"modelId":"gemini-4-neon-high"}]}}"""
private const val OPEN_NEW = """      say '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":$SESSION_DATA}'"""
private const val UNKNOWN =
    """      say '{"jsonrpc":"2.0","id":'"${'$'}id"',"error":{"code":-32602,"message":"unknown"}}'"""
private const val RESUMED = """      say '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":$SESSION_DATA}'"""
private const val FILES =
    """"files":{"command":"/usr/local/bin/files-mcp","args":["--root","/w"],"env":{"T":"sekret-1"}}"""
private const val FILES_ACP =
    """{"name":"files","command":"/usr/local/bin/files-mcp","args":["--root","/w"],""" +
        """"env":[{"name":"T","value":"sekret-1"}]}"""
private const val DOCS =
    """"docs":{"type":"http","url":"https://mcp.example.test/docs",""" +
        """"headers":{"Authorization":"Bearer sekret-2"}}"""
private const val DOCS_ACP =
    """{"name":"docs","type":"http","url":"https://mcp.example.test/docs",""" +
        """"headers":[{"name":"Authorization","value":"Bearer sekret-2"}]}"""
private const val ODD = """"odd":{"type":"websocket","url":"ws://mcp.example.test/odd"}"""
private const val EXTRA = """"extra":{"type":"http","url":"https://mcp.example.test/extra"}"""
private const val MCP_INIT =
    """{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}"""
private const val MCP_INIT_OK =
    """{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}"""
private const val MCP_NOTIF = """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}"""
