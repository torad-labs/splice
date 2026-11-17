// NEW: the harness (docs/specs/harness.md, decision 13). One call of one tool of an MCP server, on a wire already open:
// the handshake, the call with the arguments and a progress token, the tool's text as the work's result, and a
// `notifications/cancelled` for a request the call stopped waiting for.
package splice.harness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.io.IOException

internal class McpConversation(private val wire: McpWire) {
    private var last = 0L

    /** Calls [tool] with [arguments], and [meta] in the call's `_meta` beside a progress token, and says how the call
     *  ended. */
    suspend fun call(tool: String, arguments: JsonObject, meta: JsonObject, progress: WorkerProgress): WorkerEnd {
        opening()?.let { return it }
        val params = buildJsonObject {
            put("name", tool)
            put("arguments", arguments)
            put(META, meta)
        }
        val answer = ask("tools/call", params, progress) ?: return WorkerEnd.Failed("it ended before the tool answered")
        return ended(answer)
    }

    /** The handshake: null when the server is ready for a call, else the failure that ended it. */
    private suspend fun opening(): WorkerEnd? {
        val answer = ask("initialize", initialization(), null) ?: return WorkerEnd.Failed("it ended before it answered")
        if (answer[ERROR] != null) return WorkerEnd.Failed("it refused to open the conversation")
        JsonScalars.str(answer[RESULT] as? JsonObject, "protocolVersion")?.let(wire::negotiated)
        wire.post(notification("notifications/initialized"))
        return null
    }

    /** Sends [method] and hears what the server says until it answers. With a [progress] the request asks for progress
     *  notifications, by a token of its own id. A call stopped while it waits tells the server so, and the stop goes on. */
    private suspend fun ask(method: String, params: JsonObject, progress: WorkerProgress?): JsonObject? {
        last += 1
        val id = JsonPrimitive(last)
        val request = buildJsonObject {
            put(VERSION_KEY, JSONRPC)
            put(ID, id)
            put(METHOD, method)
            put(PARAMS, if (progress == null) params else asking(params, id))
        }
        try {
            return wire.ask(request) { message -> heard(message, id, progress) }
        } catch (stopped: CancellationException) {
            withContext(NonCancellable) { cancelled(id) }
            throw stopped
        }
    }

    /** [params] with [token] as the token its `_meta` asks progress by. */
    private fun asking(params: JsonObject, token: JsonElement): JsonObject {
        val meta = params[META] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(params + (META to JsonObject(meta + ("progressToken" to token))))
    }

    /** What the server says that is not the answer: a ping is answered, any other request of its is one this client does
     *  not have, and a notification of progress on [token] says its message, when it has one. */
    private suspend fun heard(message: JsonObject, token: JsonElement, progress: WorkerProgress?) {
        val method = JsonScalars.str(message, METHOD) ?: return
        val id = message[ID]
        when {
            id != null && method == "ping" -> wire.post(reply(id, RESULT, JsonObject(emptyMap())))
            id != null -> wire.post(reply(id, ERROR, unknown(method)))
            method == "notifications/progress" -> progressed(message[PARAMS] as? JsonObject, token, progress)
        }
    }

    private fun progressed(params: JsonObject?, token: JsonElement, progress: WorkerProgress?) {
        val said = if (params?.get("progressToken") == token) JsonScalars.str(params, "message") else null
        if (progress != null && !said.isNullOrBlank()) progress.said(said)
    }

    /** The server is told it need not answer request [id]; one that cannot be told is no reason to hold the stop. */
    private suspend fun cancelled(id: JsonElement) {
        withTimeoutOrNull(TELL_MS) {
            try {
                wire.post(
                    buildJsonObject {
                        put(VERSION_KEY, JSONRPC)
                        put(METHOD, "notifications/cancelled")
                        put(
                            PARAMS,
                            buildJsonObject {
                                put("requestId", id)
                                put("reason", "splice stopped waiting")
                            },
                        )
                    },
                )
            } catch (_: IOException) {
                // the server is already gone
            }
        }
    }

    /** The text of the tool's answer is the work's result; an error, or no text in it, is a failure. */
    private fun ended(answer: JsonObject): WorkerEnd {
        val result = answer[RESULT] as? JsonObject
        val texts = (result?.get("content") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { JsonScalars.str(it, "type") == "text" }
            .mapNotNull { JsonScalars.str(it, "text") }
        return when {
            answer[ERROR] != null -> WorkerEnd.Failed("the server answered an error")
            result == null -> WorkerEnd.Failed("its answer holds no result")
            (result["isError"] as? JsonPrimitive)?.booleanOrNull == true ->
                WorkerEnd.Failed("the tool answered an error result")
            texts.isEmpty() -> WorkerEnd.Failed("its result holds no text")
            else -> WorkerEnd.Result(texts.joinToString("\n"))
        }
    }

    private fun initialization() = buildJsonObject {
        put("protocolVersion", PROTOCOL)
        put("capabilities", JsonObject(emptyMap()))
        put(
            "clientInfo",
            buildJsonObject {
                put("name", "splice")
                put("version", "1")
            },
        )
    }

    private fun notification(method: String) = buildJsonObject {
        put(VERSION_KEY, JSONRPC)
        put(METHOD, method)
    }

    private fun reply(id: JsonElement, key: String, value: JsonObject) = buildJsonObject {
        put(VERSION_KEY, JSONRPC)
        put(ID, id)
        put(key, value)
    }

    private fun unknown(method: String) = buildJsonObject {
        put("code", METHOD_NOT_FOUND)
        put("message", "Method not found: $method")
    }
}

private const val VERSION_KEY = "jsonrpc"
private const val JSONRPC = "2.0"
private const val PROTOCOL = "2025-06-18"
private const val METHOD_NOT_FOUND = -32601
private const val ID = "id"
private const val METHOD = "method"
private const val PARAMS = "params"
private const val RESULT = "result"
private const val ERROR = "error"
private const val META = "_meta"

/** why: a server that is asked to stop and does not hear in this long never will, and the stop is not held for it. */
private const val TELL_MS = 1_000L
