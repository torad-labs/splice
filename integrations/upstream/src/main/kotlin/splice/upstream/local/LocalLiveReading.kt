// NEW: v0.4.0 FEATURES.md §10 — what one live probe reply PROVES. A tool call is proven by shape
// (choices[0].delta.tool_calls[].function.name in a stream chunk, or choices[0].message.tool_calls in
// a plain reply, naming the ping tool), never by marker strings: an error chunk whose message text
// mentions tool_calls and ping is not a call. A non-200 reply is described by its status class and
// size only — the body is the runtime's and can echo headers, paths or terminal controls into the
// doctor. Split from LocalRuntimeProbe.kt (review 2026-09-14).
package splice.upstream.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.upstream.transport.LocalHttpReply

// V4-103: the five error statuses this file classifies come from splice.core.wire.HttpStatus, the
// single declaration site (kt-http-status-single-source). This file MOVED here from :app carrying
// its own copies — exactly the drift the rule exists for, since these are compared against a live
// runtime's reply and a drifted spelling would silently reclassify a refused credential as a
// runtime error. HTTP_OK stays local because HttpStatus declares no 2xx constant; the wall is
// silent on it and inlining the 200 would only move the literal.
private const val HTTP_OK = 200
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
        status == HttpStatus.NOT_FOUND -> "no chat completions at this path"
        status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN -> "credential refused"
        status >= HttpStatus.INTERNAL_SERVER_ERROR -> "runtime error"
        status >= HttpStatus.BAD_REQUEST -> "request refused"
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
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): an SSE chunk that is not a JSON object cannot carry a tool call (the header's whole point), so null is the complete reading; the non-200 detail above is what reaches the operator.
        Cancellables.runCatchingCancellable { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
