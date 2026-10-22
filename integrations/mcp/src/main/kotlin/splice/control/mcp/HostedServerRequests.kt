package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Reverse requests can burst with one child frame; 64 bounds memory while leaving the stdout pump non-blocking. */
private const val SERVER_REPLY_QUEUE_CAPACITY = 64

internal fun interface ServerRequestId {
    operator fun invoke(): Long
}

/** Owns reverse reply dispatch, which holds its exact generation before a bounded worker submits stdin work. */
internal class HostedServerRequests(
    name: String,
    private val nextRequestId: ServerRequestId,
    private val codec: JsonRpcCodec,
    private val sink: NotificationSink,
    private val writer: McpChildWriter,
    private val budget: McpRequestBudget,
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
                this.name = "mcp-host-reply-$name"
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** Retention precedes queueing, so the replacement fence sees a reverse request before it can commit. */
    fun dispatch(child: Process, msg: JsonObject) {
        if (closed.get()) return
        val requestId = nextRequestId()
        if (!writer.retain(requestId, child)) {
            reject(child, msg, requestId)
            return
        }
        if (codec.method(msg) == "roots/list") sink.onRootsAsked()
        try {
            replies.execute {
                if (!closed.get()) writer.reply(codec.serverReply(msg), requestId, child, budget())
            }
        } catch (_: RejectedExecutionException) {
            reject(child, msg, requestId)
        }
    }

    /** Saturation answers this exact reverse id through the bounded child writer without inferring child exit. */
    private fun reject(child: Process, msg: JsonObject, requestId: Long) {
        if (!closed.get()) {
            writer.reply(
                codec.error(
                    msg.getValue("id"),
                    RPC_SERVER_EXITED,
                    "hosted MCP server reverse request capacity is full",
                ),
                requestId,
                child,
                budget(),
            )
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) replies.shutdownNow()
    }
}
