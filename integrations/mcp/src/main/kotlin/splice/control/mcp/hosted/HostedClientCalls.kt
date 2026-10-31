package splice.control.mcp.hosted

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.control.mcp.HostClock
import splice.control.mcp.HostedServer
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.ListingAsk
import splice.control.mcp.McpChildWriter
import splice.control.mcp.McpHostConfig
import splice.control.mcp.McpResult
import splice.control.mcp.McpWriteTarget
import splice.control.mcp.Pending
import splice.control.mcp.ProgressTokens
import splice.control.mcp.RPC_SERVER_ERROR
import splice.control.mcp.RPC_SERVER_EXITED
import splice.control.mcp.RPC_TIMED_OUT
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Owns client request and notification forwarding once the host has ensured the child is started. */
internal class HostedClientCalls(
    private val name: String,
    private val runtime: Runtime,
    private val ids: AtomicLong,
    private val pending: ConcurrentHashMap<Long, Pending>,
    private val reservations: ConcurrentHashMap<RequestId, Pending>,
    private val progress: ProgressTokens,
) {
    internal class Deadline(
        private val clock: HostClock,
        budget: Duration,
    ) {
        private val expiresAt = clock.millis() + budget.inWholeMilliseconds

        fun remaining(): Duration = (expiresAt - clock.millis()).coerceAtLeast(0L).milliseconds
    }

    internal fun interface Target {
        fun target(): McpWriteTarget?
    }

    internal data class Runtime(
        val config: McpHostConfig,
        val codec: JsonRpcCodec,
        val writer: McpChildWriter,
        val target: Target,
        val log: LogSink,
    )

    internal data class RequestId(val sessionId: String, val clientId: JsonElement)

    private data class Forward(
        val hostId: Long,
        val child: Process,
        val request: JsonObject,
        val pending: Pending,
        val clientId: JsonElement,
    )

    suspend fun forward(sessionId: String, clientId: JsonElement, request: JsonObject): JsonObject {
        val deadline = Deadline(runtime.config.clock, runtime.config.requestTimeout())
        val hostId = ids.getAndIncrement()
        val (out, token) = progress.outbound(request, hostId)
        val slot = Pending(sessionId, clientId, token)
        val reservation = RequestId(sessionId, clientId)
        if (reservations.putIfAbsent(reservation, slot) != null) {
            return runtime.codec.error(
                clientId,
                RPC_SERVER_EXITED,
                "request id is already in flight for this session",
            )
        }
        val child = runtime.target.target()?.child
        if (child == null) {
            reservations.remove(reservation, slot)
            return runtime.codec.error(clientId, RPC_SERVER_EXITED, "hosted MCP server '$name' is not ready")
        }
        val forwarded = Forward(hostId, child, out, slot, clientId)
        pending[hostId] = slot
        return try {
            awaitAnswer(forwarded, deadline).also { answer ->
                HostedMcpResults(name, runtime.config.runtime, runtime.log).capture(request, answer)
            }
        } catch (error: CancellationException) {
            slot.terminateProgress()
            throw error
        }
    }

    private suspend fun awaitAnswer(forwarded: Forward, deadline: Deadline): JsonObject {
        val writeBudget = deadline.remaining()
        if (writeBudget <= Duration.ZERO) {
            if (pending.remove(forwarded.hostId, forwarded.pending)) {
                reservations.remove(
                    RequestId(forwarded.pending.sessionId, forwarded.pending.clientId),
                    forwarded.pending,
                )
            }
            return timedOut(forwarded.clientId, forwarded.pending)
        }
        val delivered = runtime.writer.request(
            runtime.codec.withId(forwarded.request, JsonPrimitive(forwarded.hostId)),
            forwarded.hostId,
            forwarded.child,
            writeBudget,
        )
        val answerBudget = deadline.remaining()
        // A charged wire ID remains cancelable until this exact generation settles it or exits.
        return when {
            !delivered -> deliveryRefused(forwarded.hostId, forwarded.pending, forwarded.clientId)
            answerBudget <= Duration.ZERO -> timedOut(forwarded.clientId, forwarded.pending)
            else -> withTimeoutOrNull(answerBudget) { forwarded.pending.answer.await() }
                ?: timedOut(forwarded.clientId, forwarded.pending)
        }
    }

    suspend fun notify(sessionId: String, msg: JsonObject): McpResult<Boolean> {
        val out = if (runtime.codec.method(msg) == "notifications/cancelled") {
            progress.cancellation(sessionId, msg, pending, runtime.codec)
        } else {
            msg
        }
        val child = runtime.target.target()?.child
        return when {
            out == null -> McpResult.Served(true)
            child == null -> McpResult.Refused("hosted MCP server '$name' is not ready")
            else -> McpResult.Served(runtime.writer.notify(out, child, runtime.config.requestTimeout()))
        }
    }

    private fun deliveryRefused(hostId: Long, slot: Pending, clientId: JsonElement): JsonObject {
        // A writer admission refusal never owned this wire id. A failed write keeps it charged for its
        // exact generation, so cancellation and eventual child-exit cleanup remain available.
        if (hostId !in runtime.writer.unsettled && pending.remove(hostId, slot)) {
            reservations.remove(RequestId(slot.sessionId, slot.clientId), slot)
        }
        return runtime.codec.error(
            clientId,
            RPC_SERVER_EXITED,
            "request delivery was not confirmed; outcome unknown, not replayed",
        )
    }

    private fun timedOut(clientId: JsonElement, slot: Pending): JsonObject {
        slot.terminateProgress()
        return runtime.codec.error(clientId, RPC_TIMED_OUT, "hosted MCP server '$name' did not answer in time")
    }

    internal class Client(
        private val host: HostedServer,
        private val calls: HostedClientCalls,
    ) {
        suspend fun call(sessionId: String, clientId: JsonElement, request: JsonObject): JsonObject {
            when (val up = host.ensureStarted()) {
                is McpResult.Refused -> return host.codec.error(clientId, RPC_SERVER_ERROR, up.reason)
                is McpResult.Served -> Unit
            }
            return host.tools.answer(request, clientId, ListingAsk { calls.forward(sessionId, clientId, request) })
        }

        suspend fun notify(sessionId: String, msg: JsonObject): McpResult<Boolean> {
            if (host.codec.method(msg) == "notifications/initialized") return McpResult.Served(true)
            return when (val up = host.ensureStarted()) {
                is McpResult.Refused -> up
                is McpResult.Served -> calls.notify(sessionId, msg)
            }
        }
    }
}
