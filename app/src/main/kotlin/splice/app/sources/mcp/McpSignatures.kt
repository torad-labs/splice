// NEW: Oct 12, 2026 — how the MCP memory measurement recognizes a running copy of a declared server: the literal
// words of its declared command, read from the same census /api/mcp reports (every source Claude Code loads).
package splice.app.sources.mcp

import kotlinx.serialization.json.JsonArray
import splice.client.mcp.McpCensusReport
import splice.client.mcp.McpDispositioned
import splice.client.mcp.McpRegistration
import splice.client.mcp.McpShareClass
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

/** One declared stdio server under the name Claude Code gives it, the words a copy's command line holds, and how the
 *  hub runs it as the census decides: [shareClass] is null where no plan decides it yet, and [why] says why it isn't
 *  shared. */
internal data class McpServerSignature(
    val name: String,
    val words: List<String>,
    val shareClass: McpShareClass? = null,
    val why: McpWhy? = null,
) {
    /** How strongly [line] is this server: the length of the words it holds, or zero when one is missing. */
    fun strength(line: String): Int = if (words.all(line::contains)) words.sumOf(String::length) else 0
}

/** Turns the census into signatures. http, sse and ws servers have no process and are left out. */
internal class McpSignatures(private val read: McpCensusRead) {
    fun all(): List<McpServerSignature> =
        read.census()?.dispositioned.orEmpty().mapNotNull { signature(it) }.distinct()

    private fun signature(d: McpDispositioned): McpServerSignature? {
        val reg = d.registration
        val command = JsonScalars.str(reg.entry, "command") ?: return null
        val args = (reg.entry["args"] as? JsonArray).orEmpty().mapNotNull { JsonScalars.str(it) }
        val words = args.filterNot { it.startsWith("-") }.flatMap(::literal)
        val name = nameOf(reg)
        return McpServerSignature(name, words.ifEmpty { literal(command.substringAfterLast('/')) }, d.shareClass, d.why)
    }

    private fun literal(arg: String): List<String> = arg.split(EXPANDED).filter { it.length >= MIN_PIECE }

    /** A plugin's server is `plugin_<plugin>_<server>` to Claude Code; its scope is the plugin's version folder. */
    private fun nameOf(reg: McpRegistration): String = when (reg.kind) {
        McpSourceKind.PLUGIN_MCP_JSON, McpSourceKind.PLUGIN_INLINE ->
            reg.scope?.parent?.fileName?.let { "plugin_${it}_${reg.name}" } ?: reg.name
        else -> reg.name
    }
}
