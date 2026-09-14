// NEW: v0.4.0 FEATURES.md §8 — the hosted-process registry. Identity is the LAUNCH TUPLE
// (command, args, env), never the name: two operator entries that expand to the same tuple are
// aliases of one process, and a name whose entry changes rebinds to a new tuple. A process lives
// while any name is bound to it; it is replaced when its last name rebinds, evicted at capacity.
// Holds the only lock in the host; McpHost never reasons about capacity itself.
package splice.control.mcp

import splice.core.launch.McpServerSpec
import splice.core.launch.McpSharing
import splice.core.util.LogSink

private const val MILLIS_PER_MINUTE = 60_000L

/** What makes two entries the same process. */
internal data class McpIdentity(val command: String, val args: List<String>, val env: Map<String, String>)

internal class HostedServers(
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val config: McpHostConfig,
    private val launcher: McpProcessLauncher,
    private val codec: JsonRpcCodec,
    private val log: LogSink,
    private val sessions: McpSessions,
) {
    private val servers = HashMap<McpIdentity, HostedServer>()
    private val bindings = HashMap<String, McpIdentity>()

    /** Servers with an initialize in flight: acquire() counts one up, release() one down; eviction
     *  never takes a reserved server, so the session minted after ensureStarted binds to a server
     *  that is still registered (review 3, 2026-09-13). */
    private val reserved = HashMap<McpIdentity, Int>()
    private val lock = Any()

    fun get(name: String): HostedServer? = synchronized(lock) { bindings[name]?.let(servers::get) }

    fun names(): List<String> = synchronized(lock) { bindings.keys.toList() }

    /** The process for [name]'s current tuple, RESERVED against eviction until [release]: shared with
     *  every alias, replaced when the tuple changed. */
    fun acquire(name: String): HostedServer {
        val spec = sharing.hostedSpec(global(), name) ?: throw McpHostException("'$name' is not a hosted MCP server")
        val id = McpIdentity(spec.command, spec.args, spec.env)
        synchronized(lock) {
            val bound = bindings[name]
            if (bound != null && bound != id) unbind(name, "configuration changed")
            bindings[name] = id
            val server = servers[id] ?: register(id, spec)
            reserved[id] = (reserved[id] ?: 0) + 1
            return server
        }
    }

    /** A new server for [id], evicting one first at capacity. Caller holds [lock]. */
    private fun register(id: McpIdentity, spec: McpServerSpec): HostedServer {
        if (servers.size >= config.maxServers) evictOne()
        return HostedServer(spec, config, launcher, codec, log) { msg ->
            namesOf(id).forEach { alias -> sessions.fanOut(alias, codec.encode(msg)) }
        }.also { servers[id] = it }
    }

    /** Ends the reservation [acquire] took; true when [server] is still bound to [name] and alive. */
    fun release(name: String, server: HostedServer): Boolean = synchronized(lock) {
        val id = servers.entries.firstOrNull { it.value === server }?.key
        if (id != null) reserved[id] = ((reserved[id] ?: 1) - 1).takeIf { it > 0 } ?: 0
        bindings[name] == id && id != null && server.alive
    }

    fun close(name: String, reason: String) {
        synchronized(lock) { unbind(name, reason) }
    }

    fun closeAll(reason: String) {
        names().forEach { close(it, reason) }
    }

    private fun namesOf(id: McpIdentity): List<String> =
        synchronized(lock) { bindings.filterValues { it == id }.keys.toList() }

    /** Drops [name]; the process goes only when no alias is left on its tuple. Caller holds [lock]. */
    private fun unbind(name: String, reason: String) {
        val id = bindings.remove(name) ?: return
        sessions.dropServer(name)
        if (bindings.none { it.value == id }) servers.remove(id)?.close(reason)
    }

    private fun evictOne() {
        val victim = servers.keys
            .filterNot { id -> (reserved[id] ?: 0) > 0 || namesOf(id).any(sessions::streaming) }
            .minByOrNull { id -> namesOf(id).maxOfOrNull(sessions::lastActivity) ?: 0L }
            ?: throw McpHostException("MCP host at capacity (${config.maxServers} servers, all streaming or starting)")
        val last = namesOf(victim).maxOfOrNull(sessions::lastActivity) ?: 0L
        val idle = (config.clock.millis() - last) / MILLIS_PER_MINUTE
        namesOf(victim).forEach { unbind(it, "evicted after $idle min idle to host a newer server") }
        servers.remove(victim)?.close("evicted (no names bound)")
    }
}
