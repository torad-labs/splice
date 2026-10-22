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
import splice.client.mcp.McpSharing
import splice.client.mcp.McpSourceKind
import splice.client.mcp.McpWhy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: the status line prints sizes in KiB, and McpDarwinBound reads ps's KiB with the same factor.
internal const val BYTES_PER_KB = 1024L

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
        val plan = sharing.plan(global.inputs())
        return buildJsonObject {
            put("hosting", sharing.enabled)
            putJsonObject("servers") {
                plan.hosted.keys.forEach { name -> putJsonObject(name) { hosted(this, name) } }
                plan.passthrough.forEach { (name, reason) ->
                    putJsonObject(name) {
                        put("eligible", false)
                        put("reason", reason)
                        plan.why[name]?.let { why(this, it) }
                    }
                }
            }
            inventory?.let { putJsonObject("sources") { sources(this, it.census()) } }
        }.toString()
    }

    /** Why [name] is not shared right now, in the words `/api/mcp` carries; null when the host serves it. */
    fun unsharedReason(name: String): String? {
        val plan = sharing.plan(global.inputs())
        return if (name in plan.hosted) null else plan.passthrough[name] ?: "no such server in your MCP configuration"
    }

    private fun sources(out: JsonObjectBuilder, report: McpCensusReport) {
        out.putJsonArray("servers") { report.dispositioned.forEach { addJsonObject { server(this, it) } } }
    }

    private fun server(out: JsonObjectBuilder, d: McpDispositioned) {
        out.put("name", d.registration.name)
        out.put("kind", wireKind(d.registration.kind))
        out.put("source_file", d.registration.sourceFile.toString())
        d.registration.scope?.let { out.put("scope", it.toString()) }
        out.put("disposition", d.disposition.name.lowercase())
        out.put("reason", d.reason)
        d.why?.let { why(out, it) }
    }

    /** The reason as a stable key the console words, with the value it names (the variable, path, cwd or URL). */
    private fun why(out: JsonObjectBuilder, why: McpWhy) {
        out.put("why", why.key)
        why.value?.let { out.put("value", it) }
    }

    private fun wireKind(kind: McpSourceKind): String = when (kind) {
        McpSourceKind.GLOBAL -> "global"
        McpSourceKind.PROJECT -> "project"
        McpSourceKind.REPO -> "repo"
        McpSourceKind.PLUGIN_MCP_JSON -> "plugin_mcp_json"
        McpSourceKind.PLUGIN_INLINE -> "plugin_inline"
    }

    /** The process's resident set from `/proc/<pid>/status` VmRSS (kB there); null where /proc has none
     *  or the process exited between the liveness check and the read. */
    private fun residentBytes(pid: Long): Long? {
        val status = Path.of("/proc/$pid/status")
        if (!Files.isReadable(status)) return null
        val lines = try {
            Files.readAllLines(status)
        } catch (_: IOException) {
            return null
        }
        val kb = lines.firstOrNull { it.startsWith("VmRSS:") }?.filter(Char::isDigit)?.toLongOrNull()
        return kb?.times(BYTES_PER_KB)
    }

    private fun hosted(out: JsonObjectBuilder, name: String) {
        out.put("eligible", true)
        val server = server(name)
        out.put("hosted", server?.alive == true)
        server?.pid?.let(::residentBytes)?.let { out.put("rss_bytes", it) }
        val live = sessions.forServer(name)
        out.put("sessions", live.size)
        val commands = live.mapNotNull { it.head }.distinct().sorted()
        out.putJsonArray("commands") { commands.forEach { add(JsonPrimitive(it)) } }
        sessions.lastActivity(name).takeIf { it > 0L }?.let { out.put("last_activity", it) }
        server?.takeIf { it.alive }?.startedAt?.let { out.put("started_at", it) }
        server?.lastError?.let { out.put("last_error", it) }
    }
}
