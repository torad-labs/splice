// NEW: the scripted stdio MCP server McpHostTest and McpRoutesTest host, as a second JVM started from the
// test's own classpath. It was a python3 script written to a temp file, which made two JVM test suites
// depend on an interpreter the build does not otherwise need (the no-python wall).
package splice.control.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path

private const val JSONRPC = "2.0"
private const val OVERFLOW_NOTIFICATIONS = 257
private const val STDERR_REPEATS = 10_000
private const val CRASH_CODE = 3
private const val RELEASE_METHOD = "notifications/fake/release"
private const val CANCELLED_METHOD = "notifications/cancelled"

/** Answers initialize, lists one `echo` tool, echoes tools/call with its own pid, and misbehaves on demand by the
 *  call's `op`: `crash` halts the process, `stderr` floods stderr, `notify` and `overflow` emit notifications,
 *  `progress`/`late-progress` report progress, `ping-me` pings the client, and `hold`, `slow` and
 *  `timeout-progress` keep the call in flight until the test releases it.
 *
 *  THE SERVER HAS NO CLOCK. A held call is released by an EVENT the test owns: a [RELEASE] notification posted
 *  through the host (counted, so one that arrives before the call is not lost), the host's own
 *  `notifications/cancelled` (the host's request timeout is production's clock, not the test's), or the end of
 *  stdin, which releases everything so a crashed test never strands the child. Lines that arrive while a call is
 *  held are answered after it, in order. With FAKE2 set the server ignores SIGTERM, so a host has to kill it. */
public object FakeMcpServer {

    /** A client notification, posted through the host, that releases one held call. */
    public const val RELEASE: String = """{"jsonrpc":"2.0","method":"$RELEASE_METHOD"}"""

    private val queued = ArrayDeque<String>()
    private var releases = 0
    private val input: BufferedReader by lazy { System.`in`.bufferedReader() }

    /** The launcher-config entry that starts this server in a child JVM; [env] rides along as the
     *  entry's env. */
    public fun entry(env: Map<String, String> = emptyMap()): JsonObject = buildJsonObject {
        put("command", Path.of(System.getProperty("java.home"), "bin", "java").toString())
        put(
            "args",
            JsonArray(
                listOf(
                    "-XX:TieredStopAtLevel=1",
                    "-XX:+UseSerialGC",
                    "-cp",
                    System.getProperty("java.class.path"),
                    FakeMcpServer::class.java.name,
                ).map(::JsonPrimitive),
            ),
        )
        if (env.isNotEmpty()) put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) }))
    }

    @JvmStatic
    public fun main(args: Array<String>) {
        check(args.isEmpty()) { "FakeMcpServer takes no arguments" }
        if (System.getenv("FAKE2") != null) sun.misc.Signal.handle(sun.misc.Signal("TERM")) { }
        while (true) {
            val line = (queued.removeFirstOrNull() ?: input.readLine())?.trim() ?: break
            if (line.isNotEmpty()) dispatch(Json.parseToJsonElement(line).jsonObject)
        }
    }

    private fun dispatch(message: JsonObject) {
        if (message["method"]?.jsonPrimitive?.contentOrNull == RELEASE_METHOD) releases++ else handle(message)
    }

    private fun handle(message: JsonObject) {
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        val id = message["id"]
        val params = message["params"]?.jsonObject
        when (method) {
            CANCELLED_METHOD -> send(
                notification("notifications/message") {
                    put("level", "info")
                    put("data", "cancelled=${params?.get("requestId")?.jsonPrimitive?.content}")
                },
            )
            "initialize" -> send(
                reply(
                    id,
                    buildJsonObject {
                        put("protocolVersion", "2025-11-25")
                        put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
                        put("serverInfo", buildJsonObject { put("name", "fake"); put("version", "1") })
                    },
                ),
            )
            "tools/list" -> send(reply(id, buildJsonObject { put("tools", JsonArray(listOf(echoTool()))) }))
            "tools/call" -> call(id, checkNotNull(params))
        }
    }

    private fun echoTool() = buildJsonObject {
        put("name", "echo")
        put("inputSchema", buildJsonObject { put("type", "object") })
    }

    private fun call(id: JsonElement?, params: JsonObject) {
        val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val op = args["op"]?.jsonPrimitive?.contentOrNull
        val token = params["_meta"]?.jsonObject?.get("progressToken") ?: JsonNull
        when (op) {
            "crash" -> Runtime.getRuntime().halt(CRASH_CODE)
            "hold" -> {
                Files.writeString(Path.of(args.getValue("text").jsonPrimitive.content), "started")
                awaitRelease()
            }
            "slow", "timeout-progress" -> awaitRelease()
            "stderr" -> System.err.run { print("synthetic-private-stderr ".repeat(STDERR_REPEATS)); flush() }
            "notify" -> send(notification("notifications/tools/list_changed"))
            "overflow" -> repeat(OVERFLOW_NOTIFICATIONS) {
                send(notification("notifications/resources/updated") { put("uri", "test://resource") })
            }
            "progress" -> send(progress(token, 1))
            "ping-me" -> send(
                buildJsonObject { put("jsonrpc", JSONRPC); put("id", "srv-1"); put("method", "ping") },
            )
        }
        val echoed = args["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        send(reply(id, echoResult(echoed)))
        if (op == "late-progress" || op == "timeout-progress") send(progress(token, 2))
    }

    private fun echoResult(echoed: String) = buildJsonObject {
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "pid=${ProcessHandle.current().pid()} echo=$echoed")
                    },
                ),
            ),
        )
    }

    /** Blocks until a release is on hand: a [RELEASE] line, the host's cancel (kept to be answered after this
     *  call), or the end of stdin. Every other line waits its turn in [queued]. */
    private fun awaitRelease() {
        while (releases == 0) {
            val line = input.readLine()?.trim() ?: return
            if (line.isEmpty()) continue
            val method = Json.parseToJsonElement(line).jsonObject["method"]?.jsonPrimitive?.contentOrNull
            if (method != RELEASE_METHOD) queued.addLast(line)
            if (method == RELEASE_METHOD || method == CANCELLED_METHOD) releases++
        }
        releases--
    }

    private fun progress(token: JsonElement, value: Int) = notification("notifications/progress") {
        put("progressToken", token)
        put("progress", value)
    }

    private fun notification(method: String, params: (JsonObjectBuilder.() -> Unit)? = null) = buildJsonObject {
        put("jsonrpc", JSONRPC)
        put("method", method)
        params?.let { put("params", buildJsonObject(it)) }
    }

    private fun reply(id: JsonElement?, result: JsonObject) =
        buildJsonObject { put("jsonrpc", JSONRPC); put("id", id ?: JsonNull); put("result", result) }.toString()

    private fun send(message: JsonObject) = send(message.toString())

    private fun send(line: String) {
        print(line + "\n")
        System.out.flush()
    }
}
