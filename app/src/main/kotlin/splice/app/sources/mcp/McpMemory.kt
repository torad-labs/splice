// NEW: Oct 12, 2026 — what MCP servers cost this machine now, and what they would cost shared (docs/specs/mcp-hub.md,
// step 5: the console's MCP section shows "what sharing saves on this machine in measured memory").
//
// A copy is one child of a Claude Code process, or of this daemon (a hosted copy), with everything under it: the
// wrapper layers count, since an `npm exec` layer is memory the server costs. A child is a copy of the declared
// server whose command words its command lines hold, the longest such match winning; a child that matches none
// (a Bash tool's shell, a hook, a language server) is not a copy. Memory now is PSS summed over every process of
// every copy, each process's RSS where its PSS can't be read. One shared copy costs the hosted copy's RSS where
// splice hosts the server, else the median copy's RSS: a copy running alone keeps every page it shares today.
package splice.app.sources.mcp

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.WallClock

// why: Claude Code runs as ~/.local/share/claude/versions/<version>, so its process is named for the version.
private const val CLAUDE_CODE_EXE = "/claude/versions/"

// why: Claude Code's Bash tool starts every command by sourcing its shell snapshot; such a shell is never a server,
// though the command it runs may name one.
private const val BASH_TOOL = "/shell-snapshots/"

/** One running copy of a server: who started it, and its memory. */
internal data class McpCopy(
    val server: String,
    val owner: Long,
    val hosted: Boolean,
    val bytes: Long,
    val rss: Long,
    val rssFallbacks: Int,
)

/** The processes that start servers, and the servers a copy can be. */
internal data class Owners(val claudes: Set<Long>, val signatures: List<McpServerSignature>)

internal class McpMemory(
    private val signatures: McpSignatures,
    private val daemon: Long = ProcessHandle.current().pid(),
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    /** GET /api/mcp/memory: every declared stdio server, its copies and sessions, its bytes now and if shared. */
    fun json(): String {
        val declared = signatures.all()
        val table = ProcTable()
        val claudes = table.pids.filter { table.exe(it)?.contains(CLAUDE_CODE_EXE) == true }.toSet()
        val copies = (claudes + daemon).flatMap { owner -> copiesUnder(table, owner, Owners(claudes, declared)) }
        val servers = declared.map { it.name }.distinct()
            .map { name -> McpServerMemory(name, copies.filter { it.server == name }) }
            .sortedByDescending { it.bytesNow }
        return buildJsonObject {
            put("measured_at", clock())
            put("claude_processes", claudes.size)
            put("sessions", copies.filterNot { it.hosted }.map { it.owner }.distinct().size)
            put("bytes_now", servers.sumOf { it.bytesNow })
            put("bytes_if_shared", servers.sumOf { it.bytesIfShared })
            put("rss_fallbacks", copies.sumOf { it.rssFallbacks })
            putJsonArray("servers") { servers.forEach { s -> addJsonObject { s.into(this) } } }
        }.toString()
    }

    /** A Claude Code process under another is measured as its own owner, never as part of a copy under its parent. */
    private fun copiesUnder(table: ProcTable, owner: Long, owners: Owners): List<McpCopy> =
        table.childrenOf(owner).mapNotNull { child ->
            if (child in owners.claudes || BASH_TOOL in table.words(child)) return@mapNotNull null
            val pids = table.subtree(child)
            val line = pids.joinToString(" ") { table.words(it) }
            val best = owners.signatures.maxByOrNull { it.strength(line) }?.takeIf { it.strength(line) > 0 }
                ?: return@mapNotNull null
            val memory = pids.mapNotNull(table::memory)
            McpCopy(
                server = best.name,
                owner = owner,
                hosted = owner == daemon,
                bytes = memory.sumOf { it.bytes },
                rss = pids.sumOf { table.rss(it) ?: 0L },
                rssFallbacks = memory.count { !it.pss },
            )
        }
}
