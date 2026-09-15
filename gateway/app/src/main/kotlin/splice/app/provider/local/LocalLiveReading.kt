// NEW: v0.4.0 FEATURES.md §10 — what one live probe reply PROVES. A tool call is proven by shape
// (choices[0].delta.tool_calls[].function.name in a stream chunk, or choices[0].message.tool_calls in
// a plain reply, naming the ping tool), never by marker strings: an error chunk whose message text
// mentions tool_calls and ping is not a call. A non-200 reply is described by its status class and
// size only — the body is the runtime's and can echo headers, paths or terminal controls into the
// doctor. Split from LocalRuntimeProbe.kt (review 2026-09-14).
package splice.app.provider.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.dialect.chat.LocalHttpReply

private const val HTTP_OK = 200
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_SERVER_ERROR = 500
private const val SSE_DATA = "data: "
private const val SSE_DONE = "[DONE]"

internal class LocalLiveReading {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(reply: LocalHttpReply): LocalLiveProbe {
        if (reply.status != HTTP_OK) {
            val detail = "HTTP ${reply.status} (${statusClass(reply.status)}, ${reply.body.length} bytes, not shown)"
            return LocalLiveProbe(false, false, detail)
        }
        val chunks = reply.body.lineSequence().filter { it.startsWith(SSE_DATA) }
            .map { it.removePrefix(SSE_DATA).trim() }.filter { it != SSE_DONE }.toList()
        val streams = chunks.isNotEmpty()
        val toolCalls = chunks.ifEmpty { listOf(reply.body) }.any { parse(it)?.let(::callsPing) == true }
        val detail = "streamed=$streams tool_calls=$toolCalls (${reply.body.length} bytes)"
        return LocalLiveProbe(streams, toolCalls, detail)
    }

    private fun statusClass(status: Int): String = when {
        status == HTTP_NOT_FOUND -> "no chat completions at this path"
        status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN -> "credential refused"
        status >= HTTP_SERVER_ERROR -> "runtime error"
        status >= HTTP_BAD_REQUEST -> "request refused"
        else -> "unexpected status"
    }

    private fun callsPing(obj: JsonObject): Boolean {
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
        return listOf("delta", "message")
            .mapNotNull { (choice[it] as? JsonObject)?.get("tool_calls") as? JsonArray }
            .flatten()
            .any { JsonScalars.str((it as? JsonObject)?.get("function") as? JsonObject, "name") == PING_TOOL }
    }

    private fun parse(text: String): JsonObject? =
        Cancellables.runCatchingCancellable { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
