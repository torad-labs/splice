package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal fun interface ServerRequestId {
    operator fun invoke(): Long
}

/** Owns reverse JSON-RPC exchanges, whose reply can block without stalling the child's stdout pump. */
internal class HostedServerRequests(
    name: String,
    private val nextRequestId: ServerRequestId,
    private val codec: JsonRpcCodec,
    private val sink: NotificationSink,
    private val writer: McpChildWriter,
) {
    private val replies = Executors.newSingleThreadExecutor { task ->
        Executors.defaultThreadFactory().newThread(task).apply {
            name = "mcp-host-reply-$name"
            isDaemon = true
        }
    }

    /** Charges the child before queueing its reply, until flush completion or this exact child exits. */
    fun dispatch(child: Process, msg: JsonObject) {
        val requestId = nextRequestId()
        if (!writer.retain(requestId, child)) return
        if (codec.method(msg) == "roots/list") sink.onRootsAsked()
        try {
            replies.execute { writer.reply(codec.serverReply(msg), requestId, child) }
        } catch (_: RejectedExecutionException) {
            // The owner is closing; the retained charge leaves only when its child exits.
        }
    }

    fun close() {
        replies.shutdown()
    }
}
