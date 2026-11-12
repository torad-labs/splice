// NEW: Oct 13, 2026 — which sessions hold each MCP server (spec §8: "ownership visible per session"). A copy Claude
// Code started belongs to the Claude Code process it runs under; a copy the hub serves belongs to every session the
// hub's front has bound to that server's holder. Both are a Claude Code pid, and the session registry names the
// session that pid runs, so a server reads the same sessions whoever runs it.
package splice.app.sources.mcp

/** The hub's sessions per server: Claude Code's name for the server, and the launch ids (the Claude Code pids) of
 *  the sessions its front has bound to that server's holders. Empty where the hub is off or saved nothing readable. */
public fun interface McpAttached {
    public fun sessions(): Map<String, Set<String>>
}

/** One session as the console names it: its id, the door to it on Sessions, and its name, null when it has none. */
internal data class McpSessionRef(val id: String, val name: String?)

/** Every live session the registry knows, by the pid of its Claude Code process, read once per measurement. */
internal fun interface McpSessionsByPid {
    fun all(): Map<Long, McpSessionRef>
}

/** Where the two halves are read: the hub's bindings and the registry's sessions. */
internal data class McpHolders(val attached: McpAttached, val sessions: McpSessionsByPid)

/** One measurement's view: the hub's bindings and the registry's sessions, each read once. */
internal class McpHeldBy(attached: McpAttached, sessions: McpSessionsByPid) {
    private val hub = attached.sessions()
    private val byPid = sessions.all()

    /** The sessions holding [server]: the owners of the copies Claude Code started, and the hub's bound launches. A
     *  pid the registry does not know (a session that ended, or one it cannot read) is left out, never named by id. */
    fun of(server: String, owners: Collection<Long>): List<McpSessionRef> {
        val launches = hub[server].orEmpty().mapNotNull(String::toLongOrNull)
        return (owners + launches).distinct().mapNotNull(byPid::get).distinctBy { it.id }
    }
}
