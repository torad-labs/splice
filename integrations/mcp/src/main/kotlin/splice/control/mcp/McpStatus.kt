// NEW: v0.4.0 FEATURES.md §8 — `/api/mcp` — eligibility straight from the planner (so the
// console shows the same reasons the materializer acted on) plus the live state of each hosted
// process. Data for the later console; no rendering here.
//
// V4-146 (2026-09-20): an optional `sources` section — the five-kind census (McpInventory), never
// cached like everything else here. `inventory` is null in every test that does not opt in, so the
// existing `hosting`/`servers` shape (the one FEATURES.md §6 documents) is byte-identical when it
// is absent; a wired daemon gets the wider picture ADDITIVELY, never in place of it.
package splice.control.mcp

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.client.mcp.McpCensusReport
import splice.client.mcp.McpDispositioned
import splice.client.mcp.McpInventory
import splice.client.mcp.McpKindCensus
import splice.client.mcp.McpSharing
import splice.client.mcp.McpSourceKind

/** The live hosted process for a server name, or null when none is running. */
internal fun interface HostedServerLookup {
    operator fun invoke(name: String): HostedServer?
}

internal class McpStatus(
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val server: HostedServerLookup,
    private val sessions: McpSessions,
    private val inventory: McpInventory? = null,
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
            inventory?.let { putJsonObject("sources") { sources(this, it.census()) } }
        }.toString()
    }

    private fun sources(out: JsonObjectBuilder, report: McpCensusReport) {
        out.putJsonObject("kinds") { report.kinds.forEach { kind(this, it) } }
        out.putJsonArray("servers") { report.dispositioned.forEach { addJsonObject { server(this, it) } } }
    }

    private fun kind(out: JsonObjectBuilder, census: McpKindCensus) {
        out.putJsonObject(wireKind(census.kind)) {
            put("roots_scanned", census.rootsScanned)
            put("files_scanned", census.filesScanned)
            put("registrations", census.registrations)
        }
    }

    private fun server(out: JsonObjectBuilder, d: McpDispositioned) {
        out.put("name", d.registration.name)
        out.put("kind", wireKind(d.registration.kind))
        out.put("source_file", d.registration.sourceFile.toString())
        d.registration.scope?.let { out.put("scope", it.toString()) }
        out.put("disposition", d.disposition.name.lowercase())
        out.put("reason", d.reason)
    }

    private fun wireKind(kind: McpSourceKind): String = when (kind) {
        McpSourceKind.GLOBAL -> "global"
        McpSourceKind.PROJECT -> "project"
        McpSourceKind.REPO -> "repo"
        McpSourceKind.PLUGIN_MCP_JSON -> "plugin_mcp_json"
        McpSourceKind.PLUGIN_INLINE -> "plugin_inline"
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
