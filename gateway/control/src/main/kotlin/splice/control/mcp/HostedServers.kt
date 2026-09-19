// NEW: v0.4.0 FEATURES.md §8 — the hosted-process registry. Identity is the LAUNCH TUPLE
// (command, args, env), never the name: two operator entries that expand to the same tuple are
// aliases of one process, and a name whose entry changes rebinds to a new tuple. A process lives
// while any name is bound to it; it is replaced when its last name rebinds, evicted at capacity.
// Holds the only lock in the host; McpHost never reasons about capacity itself.
package splice.control.mcp

import kotlinx.serialization.json.JsonObject
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

    /** Handshakes, requests and stream attachments hold a lease until release(); eviction never
     *  takes a reserved process. Every alias shares the same launch-tuple reservation count. */
    private val reserved = HashMap<McpIdentity, Int>()
    private val lock = Any()
    private var closed = false
    private val stops = McpProcessStop()

    fun get(name: String): HostedServer? = synchronized(lock) { bindings[name]?.let(servers::get) }

    /** Lease an existing process before sending, under the same lock eviction uses. */
    fun reserve(name: String): HostedServer? = synchronized(lock) {
        val id = bindings[name]
        val server = id?.let(servers::get)?.takeUnless { closed }
        if (server != null) reserved[server.identity] = (reserved[server.identity] ?: 0) + 1
        server
    }

    /** Recheck idle state atomically with unbinding; a request lease always wins against eviction. */
    fun sweep(now: Long, idleMillis: Long) {
        val closing = Closing()
        synchronized(lock) {
            bindings.keys.filterNot(::reserved).forEach { sessions.expire(it, now, idleMillis) }
            bindings.keys.toList().filterNot { reserved(it) || sessions.busy(it, now, idleMillis) }
                .forEach { unbind(it, "idle for ${idleMillis / MILLIS_PER_MINUTE} min", closing) }
        }
        closing.run()
    }

    fun names(): List<String> = synchronized(lock) { bindings.keys.toList() }

    /** The process for [name]'s current tuple, RESERVED against eviction until [release]: shared with
     *  every alias, replaced when the tuple changed. */
    fun acquire(name: String): HostedServer {
        val spec = sharing.hostedSpec(global(), name) ?: throw McpHostException("'$name' is not a hosted MCP server")
        val id = McpIdentity(spec.command, spec.args, spec.env)
        val closing = Closing()
        val server = synchronized(lock) {
            if (closed) throw McpHostException("MCP host is stopping")
            val bound = bindings[name]
            if (bound != null && bound != id) unbind(name, "configuration changed", closing)
            val server = servers[id] ?: register(id, spec, closing)
            bindings[name] = id
            reserved[id] = (reserved[id] ?: 0) + 1
            server
        }
        closing.run()
        return server
    }

    /** A new server for [id], evicting one first at capacity. Caller holds [lock]. */
    private fun register(id: McpIdentity, spec: McpServerSpec, closing: Closing): HostedServer {
        if (servers.size >= config.maxServers) evictOne(closing)
        val sink = object : NotificationSink {
            override fun onNotification(msg: JsonObject) {
                namesOf(id).forEach { alias -> sessions.fanOut(alias, codec.encode(msg)) }
            }

            override fun onProgress(sessionId: String, msg: JsonObject) {
                namesOf(id).forEach { alias -> sessions.deliver(alias, sessionId, codec.encode(msg)) }
            }
        }
        return HostedServer(spec, config, launcher, codec, log, sink).also { servers[id] = it }
    }

    /** Ends the reservation [acquire] took; true when [server] is still bound to [name] and alive. */
    fun release(name: String, server: HostedServer): Boolean = synchronized(lock) {
        // The identity comes from the server itself, never from a scan of `servers`: a server
        // unbound between acquire and release (a sweep, a tuple change) is no longer in the map,
        // and its reservation must still end or eviction refuses newcomers until restart
        // (review 2026-09-14). A count at zero leaves the map, so `reserved` cannot grow forever.
        val id = server.identity
        val left = (reserved[id] ?: 1) - 1
        if (left > 0) reserved[id] = left else reserved.remove(id)
        bindings[name] == id && servers[id] === server && server.alive
    }

    /** Is [name]'s process leased? The idle sweep must not retire it or its sessions mid-operation. */
    fun reserved(name: String): Boolean = synchronized(lock) {
        bindings[name]?.let { (reserved[it] ?: 0) > 0 } ?: false
    }

    fun closeAll(reason: String) {
        val closing = synchronized(lock) {
            closed = true
            val snapshot = servers.values.toList()
            bindings.keys.forEach(sessions::dropServer)
            bindings.clear()
            servers.clear()
            snapshot
        }
        stops.await(closing.mapNotNull { it.close(reason, wait = false) })
    }

    private fun namesOf(id: McpIdentity): List<String> =
        synchronized(lock) { bindings.filterValues { it == id }.keys.toList() }

    /** Drops [name]; the process goes only when no alias is left on its tuple. Caller holds [lock];
     *  the close itself is queued on [closing] and runs AFTER the lock is released, because a child
     *  that ignores TERM holds tearDown for its 2 s grace and every other request on the host waited
     *  behind it (review 2026-09-14). */
    private fun unbind(name: String, reason: String, closing: Closing) {
        val id = bindings.remove(name) ?: return
        sessions.dropServer(name)
        if (bindings.none { it.value == id }) servers.remove(id)?.let { closing.add(it, reason) }
    }

    /** The servers a registry change unbound, closed once the caller has let go of [lock]. */
    private class Closing {
        private val queued = mutableListOf<Pair<HostedServer, String>>()

        fun add(server: HostedServer, reason: String) {
            queued += server to reason
        }

        fun run() {
            queued.forEach { (server, reason) -> server.close(reason) }
        }
    }

    private fun evictOne(closing: Closing) {
        val victim = servers.keys
            .filterNot { id -> (reserved[id] ?: 0) > 0 || namesOf(id).any(sessions::streaming) }
            .minByOrNull { id -> namesOf(id).maxOfOrNull(sessions::lastActivity) ?: 0L }
            ?: throw McpHostException("MCP host at capacity (${config.maxServers} servers, all streaming or starting)")
        val last = namesOf(victim).maxOfOrNull(sessions::lastActivity) ?: 0L
        val idle = (config.clock.millis() - last) / MILLIS_PER_MINUTE
        namesOf(victim).forEach { unbind(it, "evicted after $idle min idle to host a newer server", closing) }
        servers.remove(victim)?.let { closing.add(it, "evicted (no names bound)") }
    }
}
