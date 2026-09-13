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

/** The live hosted process for a server name, or null when none is running. */
internal fun interface HostedServerLookup {
    operator fun invoke(name: String): HostedServer?
}

internal class McpStatus(
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val server: HostedServerLookup,
    private val sessions: McpSessions,
) {
    fun json(): String {
        val plan = sharing.plan(global())
        return buildJsonObject {
            put("hosting", sharing.enabled)
            putJsonObject("servers") {
                plan.hosted.keys.forEach { name -> putJsonObject(name) { hosted(this, name) } }
                plan.passthrough.forEach { (name, reason) ->
                    putJsonObject(name) {
                        put("eligible", false)
                        put("reason", reason)
                    }
                }
            }
        }.toString()
    }

    private fun hosted(out: JsonObjectBuilder, name: String) {
        out.put("eligible", true)
        val server = server(name)
        out.put("hosted", server?.alive == true)
        server?.pid?.let { out.put("pid", it) }
        val live = sessions.forServer(name)
        out.put("sessions", live.size)
        out.putJsonArray("session_ids") { live.forEach { add(JsonPrimitive(it.id)) } }
        out.put("streams", live.sumOf { it.openStreams.get() })
        server?.startedAt?.takeIf { it > 0 }?.let { out.put("started_at", it) }
        live.maxOfOrNull { it.lastActivity }?.let { out.put("last_activity", it) }
        out.put("restarts", server?.restarts ?: 0)
        server?.lastError?.let { out.put("last_error", it) }
    }
}
