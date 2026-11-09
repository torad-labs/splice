package splice.control.mcp.observation

import kotlinx.serialization.json.JsonObject
import splice.control.mcp.McpAdmission
import splice.control.mcp.McpReply
import splice.core.perf.TimedWork
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.JsonScalars
import splice.core.util.MonoClock

/** Lookup uses the session's actual head. A missing inference parent stays absent. */
public fun interface McpPerfRecorders {
    public fun recorder(head: String?): PerfRecorder?
}

internal object McpPerfObservations {
    fun start(recorders: McpPerfRecorders?, head: String?, session: String?): McpPerfInvocation? =
        recorders?.recorder(head)?.tool(
            PerfScope(head = head ?: "unknown", transport = "mcp", work = "tool", local = true),
            PerfLinks(sessionId = session),
        )?.let(::McpPerfInvocation)

    suspend fun admitted(
        invocation: McpPerfInvocation?,
        admission: TimedWork<McpAdmission?>,
        route: McpAdmittedAction,
    ): McpReply = if (invocation == null) {
        route.perform(admission())
    } else {
        invocation.run {
            val started = MonoClock.nowMs()
            val admitted = admission()
            invocation.phase(mapOf("tool_queue_ms" to elapsed(started)))
            route.perform(admitted)
        }
    }

    fun elapsed(startedAt: Long): Double = (MonoClock.nowMs() - startedAt).coerceAtLeast(0).toDouble()

    fun outcome(answer: JsonObject): String {
        val result = answer["result"] as? JsonObject
        return if (answer.containsKey("error") || JsonScalars.str(result, "isError") == "true") "bad" else "good"
    }
}

/** Routing retains admission ownership; observation only surrounds its acquisition and response. */
internal fun interface McpAdmittedAction {
    suspend fun perform(admission: McpAdmission?): McpReply
}
