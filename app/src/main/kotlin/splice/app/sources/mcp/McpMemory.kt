// NEW: Oct 12, 2026 — what MCP servers cost this machine now, and what they would cost shared (docs/specs/mcp-hub.md,
// step 5: the console's MCP section shows "what sharing saves on this machine in measured memory").
//
// A copy is one child of a Claude Code process, or a copy splice hosts, with everything under it: the
// wrapper layers count, since an `npm exec` layer is memory the server costs. A child is a copy of the declared
// server whose command words its command lines hold, the longest such match winning; a child that matches none
// (a Bash tool's shell, a hook, a language server) is not a copy. Memory now is PSS summed over every process of
// every copy, each process's RSS where its PSS can't be read. A hosted copy is a child of this daemon, or the root of a
// scope in the MCP slice: the hub's holders run under `systemd-run --scope --slice=<mcpSlice>` (HubHolders.kt), so
// their parent is the user's systemd and never the daemon, and a holder outlives the daemon that started it. One
// shared copy costs the hosted copy's RSS where splice hosts the server, else the median copy's RSS: a copy running
// alone keeps every page it shares today.
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
    /** The MCP slice the hub's holders run in (the mcpSlice knob). */
    private val slice: String,
    /** The hub's bound sessions and the registry's, read once per measurement to name who holds each server. */
    private val holders: McpHolders,
    private val daemon: Long = ProcessHandle.current().pid(),
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    /** GET /api/mcp/memory: every declared stdio server, its copies and sessions, its bytes now and if shared. */
    fun json(): String {
        val declared = signatures.all()
        val table = ProcTable()
        val claudes = table.pids.filter { table.exe(it)?.contains(CLAUDE_CODE_EXE) == true }.toSet()
        val owners = Owners(claudes, declared)
        val held = table.pids.filter { table.inSlice(it, slice) }.toSet()
        // each scope's first process, the holder: its parent is outside the slice. A daemon child placed in the slice
        // is already one of the daemon's own, so it is counted there once
        val holderPids = held.filter { table.parentOf(it) !in held && table.parentOf(it) != daemon }
        val copies = (claudes + daemon).flatMap { owner -> copiesUnder(table, owner, owners) } +
            holderPids.mapNotNull { copyOf(table, it, daemon, owners) }
        val heldBy = McpHeldBy(holders.attached, holders.sessions)
        // one server can be declared in several files; the one a plan decides speaks for it
        val servers = declared.groupBy { it.name }
            .map { (name, sigs) ->
                val decided = sigs.firstOrNull { it.shareClass != null } ?: sigs.first()
                val own = copies.filter { it.server == name }
                McpServerMemory(decided, own, heldBy.of(name, own.filterNot { it.hosted }.map { it.owner }))
            }
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
        table.childrenOf(owner).mapNotNull { child -> copyOf(table, child, owner, owners) }

    /** [root] and everything under it as one copy of the server its command lines name best, or null for none. */
    private fun copyOf(table: ProcTable, root: Long, owner: Long, owners: Owners): McpCopy? {
        if (root in owners.claudes || BASH_TOOL in table.words(root)) return null
        val pids = table.subtree(root)
        val line = pids.joinToString(" ") { table.words(it) }
        val best = owners.signatures.maxByOrNull { it.strength(line) }?.takeIf { it.strength(line) > 0 } ?: return null
        val memory = pids.mapNotNull(table::memory)
        return McpCopy(
            server = best.name,
            owner = owner,
            hosted = owner == daemon,
            bytes = memory.sumOf { it.bytes },
            rss = pids.sumOf { table.rss(it) ?: 0L },
            rssFallbacks = memory.count { !it.pss },
        )
    }
}
