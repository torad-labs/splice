// NEW: v0.4.0 FEATURES.md §8 — a forwarded request waiting for the child, and where the child's
// unsolicited notifications go. Split from HostedServer.kt (concentration, 2026-09-13).
package splice.control.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val RPC_SERVER_EXITED = -32000
private const val PARAMS = "params"
private const val PROGRESS_TOKEN = "progressToken"
private const val HOST_TOKEN_PREFIX = "splice-"

/** A forwarded request waiting for the child: who asked, under which client id, and the progress
 *  token the client attached (the child sees the host's own; see [ProgressTokens]). */
internal class Pending(val sessionId: String, val clientId: JsonElement, val progressToken: JsonElement? = null) {
    val answer = CompletableDeferred<JsonObject>()

    /** The answer the caller gets when the child never will answer: an error under ITS id. */
    fun fail(codec: JsonRpcCodec, message: String) {
        answer.complete(codec.error(clientId, RPC_SERVER_EXITED, message))
    }
}

/** Where the child's unsolicited notifications go: fanned out to every session, except a progress
 *  notification, which belongs to the one session whose request carries its token. */
internal interface NotificationSink {
    fun onNotification(msg: JsonObject)
    fun onProgress(sessionId: String, msg: JsonObject)
}

/** Progress tokens are the CLIENT's: two sessions may both send "1", and the child would answer
 *  both with one stream of notifications nobody could tell apart. Outbound, the token becomes the
 *  host's request id (unique per child); inbound, the pending slot maps it back and names the
 *  session it goes to (review 2026-09-14). */
internal class ProgressTokens {
    /** The request as the child sees it, plus the client's original token when it had one. */
    fun outbound(request: JsonObject, hostId: Long): Pair<JsonObject, JsonElement?> {
        val params = request[PARAMS] as? JsonObject
        val meta = params?.get("_meta") as? JsonObject
        val token = meta?.get(PROGRESS_TOKEN) ?: return request to null
        val renamed = JsonObject(meta + (PROGRESS_TOKEN to JsonPrimitive(HOST_TOKEN_PREFIX + hostId)))
        return JsonObject(request + (PARAMS to JsonObject(params + ("_meta" to renamed)))) to token
    }

    /** For a progress notification carrying a host token: its owner and the message with the client's
     *  token restored. Missing ownership means drop, including progress after completion or timeout. */
    fun owner(msg: JsonObject, pending: Map<Long, Pending>): Pair<Pending, JsonObject>? {
        val params = msg[PARAMS] as? JsonObject ?: return null
        val token = (params[PROGRESS_TOKEN] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val slot = token?.takeIf { it.startsWith(HOST_TOKEN_PREFIX) }
            ?.removePrefix(HOST_TOKEN_PREFIX)?.toLongOrNull()?.let(pending::get)
        val original = slot?.progressToken ?: return null
        return slot to JsonObject(msg + (PARAMS to JsonObject(params + (PROGRESS_TOKEN to original))))
    }
}
