// NEW (v0.4.0, FEATURES.md §8): `/api/mcp` — eligibility straight from the planner (so the
// console shows the same reasons the materializer acted on) plus the live state of each hosted
// process. Data for the later console; no rendering here.
package splice.control.mcp

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.launch.McpSharing

internal class McpStatus(
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val server: (String) -> HostedServer?,
    private val sessions: McpSessions,
) {
    fun json(): String {
        val plan = sharing.plan(global())
        return buildJsonObject {
            put("hosting", sharing.enabled)
            putJsonObject("servers") {
                plan.hosted.keys.forEach { name -> putJsonObject(name) { hosted(name) } }
                plan.passthrough.forEach { (name, reason) ->
                    putJsonObject(name) {
                        put("eligible", false)
                        put("reason", reason)
                    }
                }
            }
        }.toString()
    }

    private fun JsonObjectBuilder.hosted(name: String) {
        put("eligible", true)
        val server = server(name)
        put("hosted", server?.alive == true)
        server?.pid?.let { put("pid", it) }
        val live = sessions.forServer(name)
        put("sessions", live.size)
        putJsonArray("session_ids") { live.forEach { add(JsonPrimitive(it.id)) } }
        put("streams", live.sumOf { it.openStreams.get() })
        server?.startedAt?.takeIf { it > 0 }?.let { put("started_at", it) }
        live.maxOfOrNull { it.lastActivity }?.let { put("last_activity", it) }
        put("restarts", server?.restarts ?: 0)
        server?.lastError?.let { put("last_error", it) }
    }
}
