// NEW: Oct 12, 2026 — how the MCP memory measurement recognizes a running copy of a declared server: the literal
// words of its declared command, read from the same census /api/mcp reports (every source Claude Code loads).
package splice.app.sources.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpCensusReport
import splice.client.mcp.McpDispositioned
import splice.client.mcp.McpPlan
import splice.client.mcp.McpRegistration
import splice.client.mcp.McpShareClass
import splice.client.mcp.McpSharing
import splice.client.mcp.McpSourceKind
import splice.client.mcp.McpWhy
import splice.core.util.JsonScalars

// why: a `${VAR}` Claude Code expands before it starts the server, so its text is never on the running command line.
private val EXPANDED = Regex("""\$\{[^}]*}""")

// why: a piece shorter than this ("/", "x") would match nearly any command line.
private const val MIN_PIECE = 3

/** The census as the measurement reads it: null where no census is wired, which measures no server. */
internal fun interface McpCensusRead {
    fun census(): McpCensusReport?
}

/** The host's sharing plan, read at call time because it is wired after the routes are built; null where unwired. */
internal fun interface McpSharingRead {
    fun sharing(): McpSharing?
}

/** One declared server under the name Claude Code gives it, the words a copy's command line holds, and how the hub
 *  runs it: [shareClass] is null where nothing classes it, and [why] says why it isn't shared. [reg] is where it is
 *  declared, which the console names it by as its Servers list does. A server at a web address has no process here:
 *  its [words] are empty and it matches no copy. */
internal data class McpServerSignature(
    val name: String,
    val words: List<String>,
    val shareClass: McpShareClass? = null,
    val why: McpWhy? = null,
    val reg: McpRegistration? = null,
) {
    val web: Boolean get() = reg != null && JsonScalars.str(reg.entry, "command") == null

    /** How strongly [line] is this server: the length of its words, zero when one is missing or it has none. */
    fun strength(line: String): Int =
        if (words.isNotEmpty() && words.all(line::contains)) words.sumOf(String::length) else 0
}

/** Turns the census into signatures. An http, sse or ws server has no process, so it is listed shared and matches no
 *  copy: it is one server at its address whoever calls it (fin, Oct 12). [sharing] is the
 *  host's own plan: it classes every declared server by the hub's rules, a plugin's or a project's as a
 *  user-scope one, whether or not a session runs in its folder (hub step 3). Null keeps the census's own class, which
 *  covers user-scope servers only. */
internal class McpSignatures(private val read: McpCensusRead, private val sharing: McpSharingRead) {
    fun all(): List<McpServerSignature> {
        val all = read.census()?.dispositioned.orEmpty()
        val (stdio, web) = all.partition { JsonScalars.str(it.registration.entry, "command") != null }
        val declared = JsonObject(stdio.associate { nameOf(it.registration) to it.registration.entry })
        val plan = sharing.sharing()?.plan(declared)
        val remote = web.map {
            McpServerSignature(nameOf(it.registration), emptyList(), McpShareClass.SHARED, reg = it.registration)
        }
        return (stdio.mapNotNull { signature(it, plan) } + remote).distinct()
    }

    private fun signature(d: McpDispositioned, plan: McpPlan?): McpServerSignature? {
        val reg = d.registration
        val command = JsonScalars.str(reg.entry, "command") ?: return null
        val args = (reg.entry["args"] as? JsonArray).orEmpty().mapNotNull { JsonScalars.str(it) }
        val words = args.filterNot { it.startsWith("-") }.flatMap(::literal)
        val name = nameOf(reg)
        val matched = words.ifEmpty { literal(command.substringAfterLast('/')) }
        return if (plan == null) {
            McpServerSignature(name, matched, d.shareClass, d.why, reg)
        } else {
            McpServerSignature(name, matched, plan.classOf(name), plan.why[name], reg)
        }
    }

    private fun literal(arg: String): List<String> = arg.split(EXPANDED).filter { it.length >= MIN_PIECE }

    /** A plugin's server is `plugin_<plugin>_<server>` to Claude Code; its scope is the plugin's version folder. */
    private fun nameOf(reg: McpRegistration): String = when (reg.kind) {
        McpSourceKind.PLUGIN_MCP_JSON, McpSourceKind.PLUGIN_INLINE ->
            reg.scope?.parent?.fileName?.let { "plugin_${it}_${reg.name}" } ?: reg.name
        McpSourceKind.GLOBAL, McpSourceKind.PROJECT, McpSourceKind.REPO -> reg.name
    }
}
