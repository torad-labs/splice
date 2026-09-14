// NEW: v0.4.0 FEATURES.md §8 — a forwarded request waiting for the child, and where the child's
// unsolicited notifications go. Split from HostedServer.kt (concentration, 2026-09-13).
package splice.control.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal const val RPC_SERVER_EXITED = -32000

/** A forwarded request waiting for the child: who asked, under which client id. */
internal class Pending(val sessionId: String, val clientId: JsonElement) {
    val answer = CompletableDeferred<JsonObject>()

    /** The answer the caller gets when the child never will answer: an error under ITS id. */
    fun fail(codec: JsonRpcCodec, message: String) {
        answer.complete(codec.error(clientId, RPC_SERVER_EXITED, message))
    }
}

/** Where the child's unsolicited notifications go: the host fans them out to every session. */
internal fun interface NotificationSink {
    fun onNotification(msg: JsonObject)
}
