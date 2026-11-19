// NEW: the MCP worker's tests (docs/specs/harness.md, decision 13). Two servers outside splice that a session's folder
// could declare, one a program on stdio and one at a URL, each answering the one tool `answer` the way its mode says and
// recording what it was sent.
package splice.harness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** What a server does with its tool's call. */
enum class Does { ANSWERS, ERROR_RESULT, NO_TEXT, RPC_ERROR, HANGS }

/** The text a server answers with, which says where the server ran. */
const val FOUND: String = "src/main.ts:10-42 holds it."

/** A program on stdio, written as a script in [dir]: it answers `initialize`, answers the tool by [does] after one
 *  progress message, and writes each call, each cancellation and its own pid to files beside it. */
class StdioMcp(private val dir: Path, private val does: Does) {
    private val script = Files.createDirectories(dir).resolve("server.sh").also {
        Files.writeString(it, SCRIPT.replace('@', '$'))
    }

    /** The entry a `.mcp.json` declares it with. */
    fun entry(): JsonObject = buildJsonObject {
        put("command", "bash")
        put("args", buildJsonArray { add(JsonPrimitive(script.toString())) })
        put("env", buildJsonObject { put("MCP_DIR", dir.toString()); put("MCP_DOES", does.name) })
    }

    /** Each call of the tool, as the JSON-RPC request the server got. */
    fun calls(): List<JsonObject> = lines("calls")

    /** Each `notifications/cancelled` the server got. */
    fun cancels(): List<JsonObject> = lines("cancels")

    fun pid(): Long? = dir.resolve("pid").takeIf(Files::exists)?.let { Files.readString(it).trim().toLongOrNull() }

    private fun lines(name: String): List<JsonObject> {
        val file = dir.resolve(name)
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
    }
}

private const val SCRIPT = """#!/bin/bash
echo @@ > "@MCP_DIR/pid"
while IFS= read -r line; do
  id=@(printf '%s' "@line" | sed -nE 's/^\{"jsonrpc":"2.0","id":([0-9]+),.*/\1/p')
  case "@line" in
    *'"method":"initialize"'*)
      printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"t","version":"1"}}}\n' "@id" ;;
    *'"method":"notifications/cancelled"'*)
      printf '%s\n' "@line" >> "@MCP_DIR/cancels" ;;
    *'"method":"tools/call"'*)
      printf '%s\n' "@line" >> "@MCP_DIR/calls"
      token=@(printf '%s' "@line" | sed -nE 's/.*"progressToken":([0-9]+).*/\1/p')
      printf '{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":%s,"progress":1,"message":"Reading the notes"}}\n' "@token"
      case "@MCP_DOES" in
        ANSWERS) printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"found in %s"}]}}\n' "@id" "@(pwd)" ;;
        ERROR_RESULT) printf '{"jsonrpc":"2.0","id":%s,"result":{"isError":true,"content":[{"type":"text","text":"locked"}]}}\n' "@id" ;;
        NO_TEXT) printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"image","data":"AA==","mimeType":"image/png"}]}}\n' "@id" ;;
        RPC_ERROR) printf '{"jsonrpc":"2.0","id":%s,"error":{"code":-32602,"message":"Unknown tool"}}\n' "@id" ;;
        HANGS) sleep 60 & ;;
      esac ;;
  esac
done
"""

/** A server at a URL, as the hub hosts one for every session: the tool answers over the post's own stream of events,
 *  after one progress message, and each post's headers are kept. With [onSession], it answers as the daemon's MCP host
 *  does instead: the call's answer is one JSON object, and its progress (a message, then a line with none) goes on the
 *  session's own stream, the GET the client opens. */
class HttpMcp(private val text: String = FOUND, private val onSession: Boolean = false) : AutoCloseable {
    private val pushes = LinkedBlockingQueue<String>()

    /** Counted down by the client as it hears each line the call pushes: the call answers once both are heard, as the
     *  host answers once the tool is done, whatever its stream carried. */
    val heard: CountDownLatch = CountDownLatch(PUSHED_LINES)

    /** The headers and body of each post the server got, oldest first. */
    val posts: MutableList<Pair<Map<String, String>, JsonObject>> = CopyOnWriteArrayList()

    /** Whether the server was told its session is over. */
    @Volatile var ended: Boolean = false

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/mcp/notes") { ex -> ex.use(::handle) }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/mcp/notes"

    fun entry(): JsonObject = buildJsonObject {
        put("type", "http")
        put("url", url)
        put("headers", buildJsonObject { put("Authorization", "Bearer the-mcp-key") })
    }

    private fun handle(ex: HttpExchange) {
        if (ex.requestMethod == "GET") return session(ex)
        if (ex.requestMethod == "DELETE") {
            ended = true
            ex.sendResponseHeaders(NO_CONTENT, -1)
            return
        }
        val body = Json.parseToJsonElement(ex.requestBody.readAllBytes().decodeToString()).jsonObject
        val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
        posts += headers to body
        val id = body["id"]
        when (body["method"]?.jsonPrimitive?.content) {
            "initialize" -> {
                ex.responseHeaders.add("Mcp-Session-Id", "session-1")
                json(ex, """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""")
            }
            "tools/call" -> called(ex, id, body["params"]?.jsonObject?.get("_meta")?.jsonObject?.get("progressToken"))
            else -> ex.sendResponseHeaders(ACCEPTED, -1)
        }
    }

    private fun called(ex: HttpExchange, id: JsonElement?, token: JsonElement?) {
        if (!onSession) return events(ex, id, token)
        val progress = """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":$token"""
        pushes.put("""$progress,"progress":1,"message":"Reading the notes"}}""")
        pushes.put("""$progress,"progress":2}}""")
        heard.await(PUSH_WAIT_S, TimeUnit.SECONDS)
        json(ex, """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"$text"}]}}""")
    }

    /** The session's stream: each line the call pushes, as an event, until the server closes. */
    private fun session(ex: HttpExchange) {
        if (!onSession) return ex.sendResponseHeaders(METHOD_NOT_ALLOWED, -1)
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(OK, 0)
        val out = ex.responseBody
        out.write(": open\n\n".toByteArray())
        out.flush()
        while (true) {
            val line = pushes.take().takeUnless { it == CLOSED } ?: return
            out.write("event: message\ndata: $line\n\n".toByteArray())
            out.flush()
        }
    }

    private fun events(ex: HttpExchange, id: JsonElement?, token: JsonElement?) {
        val progress = """{"jsonrpc":"2.0","method":"notifications/progress","params":""" +
            """{"progressToken":$token,"progress":1,"message":"Reading the notes"}}"""
        val result = """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"$text"}]}}"""
        val stream = "event: message\ndata: $progress\n\nevent: message\ndata: $result\n\n"
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(OK, 0)
        ex.responseBody.use { it.write(stream.toByteArray()) }
    }

    private fun json(ex: HttpExchange, text: String) {
        ex.responseHeaders.add("Content-Type", "application/json")
        val bytes = text.toByteArray()
        ex.sendResponseHeaders(OK, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        pushes.put(CLOSED)
        server.stop(0)
    }
}

private const val OK = 200
private const val METHOD_NOT_ALLOWED = 405
private const val CLOSED = ""
private const val PUSH_WAIT_S = 5L
private const val PUSHED_LINES = 2
private const val ACCEPTED = 202
private const val NO_CONTENT = 204
