// NEW (v0.4.0, FEATURES.md §8): the hosted-process registry — one HostedServer per server name
// while its spec is unchanged, replaced when the operator edits the entry, evicted at capacity.
// Holds the only lock in the host; McpHost never reasons about capacity itself.
package splice.control.mcp

import splice.core.launch.McpServerSpec
import splice.core.launch.McpSharing
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap

private const val MILLIS_PER_MINUTE = 60_000L

internal class HostedServers(
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val config: McpHostConfig,
    private val launcher: McpProcessLauncher,
    private val codec: JsonRpcCodec,
    private val log: LogSink,
    private val sessions: McpSessions,
) {
    private val servers = ConcurrentHashMap<String, HostedServer>()
    private val specs = ConcurrentHashMap<String, McpServerSpec>()
    private val lock = Any()

    /** Read-only view for status. */
    val live: Map<String, HostedServer> get() = servers

    fun get(name: String): HostedServer? = servers[name]

    fun names(): List<String> = servers.keys.toList()

    /** The hosted server for [name]: reused while its spec is unchanged, else replaced; evicts at capacity. */
    fun acquire(name: String): HostedServer {
        val spec = sharing.hostedSpec(global(), name) ?: throw McpHostException("'$name' is not a hosted MCP server")
        synchronized(lock) {
            val existing = servers[name]
            if (existing != null && specs[name] == spec) return existing
            existing?.let { close(name, "configuration changed") }
            if (servers.size >= config.maxServers) evictOne()
            val fresh = HostedServer(spec, config, launcher, codec, log) { sessions.fanOut(name, codec.encode(it)) }
            servers[name] = fresh
            specs[name] = spec
            return fresh
        }
    }

    fun close(name: String, reason: String) {
        servers.remove(name)?.close(reason)
        specs.remove(name)
        sessions.dropServer(name)
    }

    fun closeAll(reason: String) {
        names().forEach { close(it, reason) }
    }

    private fun evictOne() {
        val victim = servers.keys
            .filterNot(sessions::streaming)
            .minByOrNull(sessions::lastActivity)
            ?: throw McpHostException("MCP host at capacity (${config.maxServers} servers, all streaming)")
        val idle = (config.clock.millis() - sessions.lastActivity(victim)) / MILLIS_PER_MINUTE
        close(victim, "evicted after $idle min idle to host a newer server")
    }
}
