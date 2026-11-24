// A Desktop session run end to end against a scripted `agy acp` that logs every request it reads: the shared rig of
// the MCP, live-switch and skills suites. [ScriptedAgy] answers each ACP method with a shell snippet, [AgyRig] runs
// DesktopAcp on it, and [AgyDriver] is Desktop's side of the pipes.
package splice.provider.antigravity

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.core.client.ClaudeProjectFolder
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.provider.antigravity.peer.DesktopPeerWiring
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path

/** An agy whose answer to each ACP method is a shell snippet run with `$id` (the request's id) and `$l` (its line). */
internal class ScriptedAgy(
    val onNew: String = RIG_OPEN_NEW,
    val onResume: String = RIG_UNKNOWN,
    val turns: List<String> = listOf("""stop "${'$'}id" end_turn"""),
    val onInitialize: String = RIG_HELLO,
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
            |    initialize)
            |$onInitialize
            |      ;;
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

/** A Desktop session in [tmp]: its home, its project folder, and every request its agy read. */
internal class AgyRig(private val tmp: Path) {
    val home: Path = tmp.resolve("home")
    val project: Path = tmp.resolve("work.dir")
    private val agyLog: Path = tmp.resolve("agy.jsonl")
    val logged = StringBuffer()

    /** The params of each [method] request agy read, in order. */
    fun asked(method: String): List<JsonObject> =
        if (!Files.exists(agyLog)) {
            emptyList()
        } else {
            Files.readAllLines(agyLog).filter(String::isNotBlank)
                .map { Json.parseToJsonElement(it).jsonObject }
                .filter { (it["method"] as? JsonPrimitive)?.takeUnless { m -> m is JsonNull }?.content == method }
                .map { it.getValue("params").jsonObject }
        }

    fun transcriptFile(id: String): Path = home.resolve(".claude").resolve("projects")
        .resolve(ClaudeProjectFolder.of(project.toString())).resolve("$id.jsonl")

    fun run(
        agy: ScriptedAgy,
        extra: List<String> = emptyList(),
        peer: DesktopPeerWiring = DesktopPeerWiring(),
        drive: suspend AgyDriver.() -> Unit,
    ): SeenOut {
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
        val driver = AgyDriver()
        return runBlocking {
            withTimeout(RIG_DEADLINE_MS) {
                val job = async(Dispatchers.IO) {
                    DesktopAcp(
                        args,
                        project,
                        env,
                        Dispatchers.IO,
                        LogSink { logged.append(it) },
                        WallClock { RIG_CLOCK },
                        listOf("sh", script.toString()),
                    ).run(driver.stdin, driver.stdout, peer = peer)
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

/** Every line Desktop received, parsed and raw. */
internal data class SeenOut(val lines: List<JsonObject>, val raw: List<String>)

/** Desktop's side of the pipes: what it writes, and every line it has received. */
internal class AgyDriver {
    private val toSession = PipedOutputStream()
    val stdin = PipedInputStream(toSession, RIG_PIPE_BYTES)
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

    suspend fun nextMcpMessage(): JsonObject = next { line ->
        line.text("type") == "control_request" &&
            (line["request"] as? JsonObject)?.text("subtype") == "mcp_message"
    }

    suspend fun answer(id: String): JsonObject = next { line ->
        line.text("type") == "control_response" && (line["response"] as? JsonObject)?.text("request_id") == id
    }.getValue("response").jsonObject

    suspend fun result(): JsonObject = next { it.text("type") == "result" }

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

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

internal const val RIG_SESSION_DATA =
    """{"sessionId":"s-1","modes":{"currentModeId":"default"},"configOptions":[""" +
        """{"id":"model","currentValue":"gemini-4-neon",""" +
        """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]}],""" +
        """"models":{"availableModels":[{"modelId":"gemini-4-neon-high"}]}}"""
internal const val RIG_OPEN_NEW = """      say '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":$RIG_SESSION_DATA}'"""
internal const val RIG_HELLO = """      say '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":{"protocolVersion":1}}'"""
internal const val RIG_UNKNOWN =
    """      say '{"jsonrpc":"2.0","id":'"${'$'}id"',"error":{"code":-32602,"message":"unknown"}}'"""
internal const val RIG_RESUMED = """      say '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":$RIG_SESSION_DATA}'"""

private const val RIG_DEADLINE_MS = 30_000L
private const val RIG_PIPE_BYTES = 1 shl 16
private const val RIG_CLOCK = 1_795_300_000_000L
