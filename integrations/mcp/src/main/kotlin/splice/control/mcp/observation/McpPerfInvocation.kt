package splice.control.mcp.observation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import splice.control.mcp.McpReply
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfToolRecorder
import splice.core.util.JsonScalars
import splice.core.util.MonoClock
import splice.core.wire.HttpStatus
import java.util.concurrent.atomic.AtomicBoolean

/** Lookup uses the session's actual head. A missing inference parent stays absent. */
public fun interface McpPerfRecorders {
    public fun recorder(head: String?): PerfRecorder?
}

/** Pending owns the independent tool handle after forwarding, even when its waiting HTTP caller leaves. */
internal class McpPerfInvocation(private val tool: PerfToolRecorder) {
    private val handedOff = AtomicBoolean()
    private val cancelled = AtomicBoolean()
    private val timedOut = AtomicBoolean()

    @Volatile private var sentAt: Long? = null

    fun transfer(): McpPerfInvocation = also { handedOff.set(true) }
    fun phase(values: Map<String, Double>) { tool.observe(PerfEventKind.TOOL_PHASE, values) }
    fun sent() { sentAt = MonoClock.nowMs() }
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) phase(mapOf("tool_cancelled" to 1.0))
    }
    fun timeout() {
        if (timedOut.compareAndSet(false, true)) phase(mapOf("tool_timeouts" to 1.0))
    }
    fun response(answer: JsonObject) { finish(McpPerfObservations.outcome(answer)) }
    fun finish(outcome: String) {
        val values = sentAt?.let { mapOf("tool_execution_ms" to McpPerfObservations.elapsed(it)) }.orEmpty()
        tool.finish(outcome, values)
    }

    suspend fun run(action: McpPerfAction): McpReply {
        var outcome = "bad"
        try {
            return withContext(McpPerfContext(this)) { action.perform() }.also {
                outcome = if (it.status in HttpStatus.OK..HttpStatus.MAX_SUCCESS) "?" else "bad"
            }
        } catch (error: CancellationException) {
            cancel()
            outcome = "cancelled"
            throw error
        } finally {
            if (!handedOff.get()) finish(outcome)
        }
    }
}

internal object McpPerfObservations {
    fun start(recorders: McpPerfRecorders?, head: String?, session: String?): McpPerfInvocation? =
        recorders?.recorder(head)?.tool(
            PerfScope(head = head ?: "unknown", transport = "mcp", work = "tool", local = true),
            PerfLinks(sessionId = session),
        )?.let(::McpPerfInvocation)

    fun elapsed(startedAt: Long): Double = (MonoClock.nowMs() - startedAt).coerceAtLeast(0).toDouble()

    fun outcome(answer: JsonObject): String {
        val result = answer["result"] as? JsonObject
        return if (answer.containsKey("error") || JsonScalars.str(result, "isError") == "true") "bad" else "good"
    }
}

internal fun interface McpPerfAction {
    suspend fun perform(): McpReply
}
