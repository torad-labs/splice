package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVER_REPLY_QUEUE_CAPACITY = 64

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
    private val failed: McpWriteFailure,
) {
    private val closed = AtomicBoolean(false)
    private val replies = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(SERVER_REPLY_QUEUE_CAPACITY),
        { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                name = "mcp-host-reply-$name"
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** Charges the child before queueing its reply, until flush completion or this exact child exits. */
    fun dispatch(child: Process, msg: JsonObject) {
        if (closed.get()) return
        val requestId = nextRequestId()
        if (!writer.retain(requestId, child)) return
        if (codec.method(msg) == "roots/list") sink.onRootsAsked()
        try {
            replies.execute {
                if (!closed.get()) writer.reply(codec.serverReply(msg), requestId, child)
            }
        } catch (_: RejectedExecutionException) {
            if (!closed.get()) failed(child, "server reply queue is full")
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) replies.shutdownNow()
    }
}
