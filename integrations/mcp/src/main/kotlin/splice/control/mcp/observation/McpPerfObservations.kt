package splice.control.mcp.observation

import kotlinx.serialization.json.JsonObject
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

    fun elapsed(startedAt: Long): Double = (MonoClock.nowMs() - startedAt).coerceAtLeast(0).toDouble()

    fun outcome(answer: JsonObject): String {
        val result = answer["result"] as? JsonObject
        return if (answer.containsKey("error") || JsonScalars.str(result, "isError") == "true") "bad" else "good"
    }
}
