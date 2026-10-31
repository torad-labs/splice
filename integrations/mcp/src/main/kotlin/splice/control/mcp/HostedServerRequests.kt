package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import splice.control.mcp.hosted.HostedClientCalls
import splice.core.util.JsonScalars
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
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

    /** Retention precedes reply submission, and the child writer owns one bounded retry until it flushes or exits. */
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

    /** Proves a decoded stdout message still belongs to the active child generation. */
    internal fun interface Generation {
        fun current(child: Process): Boolean
    }

    internal data class DispatchRuntime(
        val codec: JsonRpcCodec,
        val writer: McpChildWriter,
        val sink: NotificationSink,
        val reservations: ConcurrentHashMap<HostedClientCalls.RequestId, Pending>,
    )

    /** Dispatches messages from one stdout generation only after its owner proves it is still current. */
    internal class Dispatcher(
        private val runtime: DispatchRuntime,
        private val requests: HostedServerRequests,
        private val progress: ProgressTokens,
        private val pending: ConcurrentHashMap<Long, Pending>,
        private val tools: ToolsListCache,
        private val generation: Generation,
    ) {
        fun dispatch(child: Process, msg: JsonObject) {
            when (runtime.codec.kind(msg)) {
                RpcKind.RESPONSE -> response(msg)
                RpcKind.REQUEST -> requests.dispatch(child, msg)
                RpcKind.NOTIFICATION -> notification(child, msg)
                RpcKind.INVALID -> Unit
            }
        }

        private fun response(msg: JsonObject) {
            val hostId = JsonScalars.str(msg["id"])?.toLongOrNull()
            hostId?.let(runtime.writer.settled::invoke)
            val slot = hostId?.let(pending::remove)
            slot?.let {
                runtime.reservations.remove(HostedClientCalls.RequestId(it.sessionId, it.clientId), it)
                it.answer.complete(runtime.codec.withId(msg, it.clientId))
            }
        }

        private fun notification(child: Process, msg: JsonObject) {
            if (!generation.current(child)) return
            if (runtime.codec.method(msg) == "notifications/progress") {
                // A missing owner is late or unknown progress, never a global notification.
                progress.owner(msg, pending)?.let { (slot, routed) ->
                    runtime.sink.onProgress(child, slot.sessionId, routed)
                }
            } else {
                ordinaryNotification(child, msg)
            }
        }

        private fun ordinaryNotification(child: Process, msg: JsonObject) {
            if (runtime.codec.method(msg) == "notifications/tools/list_changed") tools.drop()
            runtime.sink.onNotification(child, msg)
        }
    }
}
