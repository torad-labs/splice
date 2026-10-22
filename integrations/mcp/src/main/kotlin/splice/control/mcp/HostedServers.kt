// NEW: v0.4.0 FEATURES.md §8 — the hosted-process registry. Identity is the LAUNCH TUPLE
// (command, args, env), never the name: two operator entries that expand to the same tuple are
// aliases of one process, and a name whose entry changes rebinds to a new tuple. A process lives
// while any name is bound to it; it is replaced when its last name rebinds, evicted at capacity.
// Holds the only lock in the host; McpHost never reasons about capacity itself.
package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpServerSpec
import splice.client.mcp.McpSharing
import splice.core.util.LogSink
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val MILLIS_PER_MINUTE = 60_000L

/** Serializes unbounded launch/adoption outside the registry lock, so shutdown cannot pass a late child. */
private class McpLaunchBarrier : McpLaunchGate {
    private val lock = ReentrantLock()
    private val stops = McpProcessStop()
    private var closed = false
    private val unresolved = mutableSetOf<McpProcessGeneration>()

    override fun launch(
        spec: McpServerSpec,
        launcher: McpProcessLauncher,
        adoption: McpLaunchAdoption,
    ): Process? = lock.withLock {
        if (closed) {
            null
        } else {
            val child = launcher(spec)
            val generation = (launcher as? McpProcessGenerationSource)?.capture(child) ?: stops.capture(child)
            if (adoption.adopt(child, generation)) {
                child
            } else {
                unresolved += stops.stopGenerations(listOf(generation))
                null
            }
        }
    }

    /** Waits through any spawn and adoption, then refuses every later launch before registry shutdown begins. */
    fun close() = lock.withLock {
        closed = true
        val retained = stops.stopGenerations(unresolved.toList())
        unresolved.clear()
        unresolved += retained
    }
}

/** What makes two entries the same process. */
internal data class McpIdentity(val command: String, val args: List<String>, val env: Map<String, String>)

private fun interface HostedServerNames {
    fun deliver(names: List<String>)
}

private fun interface HostedServerNotificationPublication {
    fun publish(server: HostedServer, child: Process, notification: HostedServerNames)
}

/** Routes a child's notifications only while the registry holds that exact published instance. */
private class HostedServerNotifications(
    private val publication: HostedServerNotificationPublication,
    private val sessions: McpSessions,
    private val codec: JsonRpcCodec,
    private val sharing: McpSharing,
    private val spec: McpServerSpec,
    private val log: LogSink,
) : NotificationSink {
    @Volatile private var owner: HostedServer? = null

    fun bind(server: HostedServer) {
        owner = server
    }

    override fun onNotification(child: Process, msg: JsonObject) {
        publish(child, HostedServerNames { names -> names.forEach { sessions.fanOut(it, codec.encode(msg)) } })
    }

    override fun onProgress(child: Process, sessionId: String, msg: JsonObject) {
        publish(
            child,
            HostedServerNames { names ->
                names.forEach { sessions.deliver(it, sessionId, codec.encode(msg)) }
            },
        )
    }

    override fun onRootsAsked() {
        sharing.rootsAsked(spec)
        log("[mcp-host] ${spec.name}: asks its client for roots, so each later session starts its own\n")
    }

    private fun publish(child: Process, notification: HostedServerNames) {
        owner?.let { publication.publish(it, child, notification) }
    }
}

internal class HostedServers(
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val config: McpHostConfig,
    private val launcher: McpProcessLauncher,
    private val codec: JsonRpcCodec,
    private val log: LogSink,
    private val sessions: McpSessions,
) {
    /** How a child's exit is reaped. Production waits on the process; a test holds one exit open. */
    internal var reaped: ChildReaped = waitForReap

    private val store = HostedServerStore()
    private val servers = store.servers
    private val bindings = store.bindings

    /** Handshakes, requests and stream attachments hold a lease until release(); eviction never
     *  takes a reserved process. Every alias shares the same launch-tuple reservation count. */
    private val reserved = store.reserved
    private val lock = store.lock
    private var closed: Boolean
        get() = store.closed
        set(value) { store.closed = value }
    private val stops = McpProcessStop()
    private val launches = McpLaunchBarrier()
    val replacements = HostedReplacements(store, sharing, global, sessions, ::create)

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
    fun acquire(name: String, admission: McpAdmission? = null): McpResult<HostedServer> {
        val spec = sharing.hostedSpec(global(), name)
            ?: return McpResult.Refused("'$name' is not a hosted MCP server")
        val id = McpIdentity(spec.command, spec.args, spec.env)
        val closing = Closing()
        val acquired = synchronized(lock) {
            val refusal = store.admissionRefusal(id, admission)
            if (refusal != null) {
                McpResult.Refused(refusal)
            } else {
                Acquisition().acquire(name, spec, closing)
            }
        }
        closing.run()
        return acquired
    }

    private fun create(spec: McpServerSpec): HostedServer {
        val id = McpIdentity(spec.command, spec.args, spec.env)
        val sink = HostedServerNotifications(
            HostedServerNotificationPublication { server, child, notification ->
                synchronized(lock) {
                    if (servers[server.identity] === server) {
                        server.enqueueIfOwned(
                            child,
                            HostedNotificationEnqueue { notification.deliver(store.namesOf(server.identity)) },
                        )
                    }
                }
            },
            sessions,
            codec,
            sharing,
            spec,
            log,
        )
        val server = HostedServer(
            spec,
            HostedServerRuntime(config, launcher, reaped, launches),
            codec,
            log,
            sink,
        )
        sink.bind(server)
        server.admission = synchronized(lock) { store.admissions.getOrPut(id) { McpAdmission() } }
        return server
    }

    /** Ends the reservation [acquire] took; true when [server] is still bound to [name] and alive. */
    fun release(name: String, server: HostedServer): Boolean = synchronized(lock) {
        releaseCurrent(name, server)
    }

    /** Publishes a session only while the same generation that supplied initialize still owns [name]. */
    fun createSession(
        name: String,
        server: HostedServer,
        initialization: HostedInitialization,
        sessionId: String?,
        head: String?,
    ): McpSession? = synchronized(lock) {
        if (!releaseCurrent(name, server, initialization)) return@synchronized null
        sessions.create(name, initialization.result, sessionId, head)
    }

    private fun releaseCurrent(
        name: String,
        server: HostedServer,
        initialization: HostedInitialization? = null,
    ): Boolean {
        // The identity comes from the server itself, never from a scan of `servers`: a server
        // unbound between acquire and release (a sweep, a tuple change) is no longer in the map,
        // and its reservation must still end or eviction refuses newcomers until restart
        // (review 2026-09-14). A count at zero leaves the map, so `reserved` cannot grow forever.
        val id = server.identity
        val left = (reserved[id] ?: 1) - 1
        if (left > 0) reserved[id] = left else reserved.remove(id)
        val published = bindings[name] == id && servers[id] === server
        return published && (initialization?.let { server.owns(it) } ?: server.alive)
    }

    /** Is [name]'s process leased? The idle sweep must not retire it or its sessions mid-operation. */
    fun reserved(name: String): Boolean = synchronized(lock) {
        bindings[name]?.let { id ->
            (reserved[id] ?: 0) > 0 || servers[id]?.let {
                it.admission.paused || it.admission.activeRequests > 0 || it.unsettled.isNotEmpty()
            } == true
        } ?: false
    }

    fun closeAll(reason: String) {
        launches.close()
        val closing = synchronized(lock) {
            closed = true
            val snapshot = (servers.values + store.candidates.values).distinct()
            store.admissions.values.forEach(McpAdmission::stop)
            store.admissions.clear()
            bindings.keys.forEach(sessions::dropServer)
            bindings.clear()
            reserved.clear()
            servers.clear()
            store.candidates.clear()
            snapshot
        }
        stops.stopGenerations(closing.mapNotNull { it.close(reason, wait = false) })
    }

    /** Drops [name]; the process goes only when no alias is left on its tuple. Caller holds [lock];
     *  the close itself is queued on [closing] and runs AFTER the lock is released, because a child
     *  that ignores TERM holds tearDown for its 2 s grace and every other request on the host waited
     *  behind it (review 2026-09-14). */
    private fun unbind(name: String, reason: String, closing: Closing) {
        val id = bindings.remove(name) ?: return
        sessions.dropServer(name)
        if (bindings.none { it.value == id }) {
            store.admissions.remove(id)
            servers.remove(id)?.let { closing.add(it, reason) }
        }
    }

    /** Keeps the ordinary acquire path separate from registry lifecycle and notification ownership. */
    private inner class Acquisition {
        fun acquire(name: String, spec: McpServerSpec, closing: Closing): McpResult<HostedServer> {
            val id = McpIdentity(spec.command, spec.args, spec.env)
            val rebind = rebind(name, id, closing)
            if (rebind != null) return rebind
            val registered = registered(id, spec, closing)
            if (registered is McpResult.Served) reserve(name, id)
            return registered
        }

        private fun rebind(name: String, id: McpIdentity, closing: Closing): McpResult.Refused? {
            val bound = bindings[name]
            if (bound == null || bound == id) return null
            if (store.rebindBusy(bound)) {
                return McpResult.Refused("MCP configuration changed while its previous server is in use")
            }
            unbind(name, "configuration changed", closing)
            return null
        }

        private fun registered(
            id: McpIdentity,
            spec: McpServerSpec,
            closing: Closing,
        ): McpResult<HostedServer> = when {
            store.candidates.containsKey(id) -> McpResult.Refused("MCP replacement is preparing this launch tuple")
            else -> servers[id]?.let { McpResult.Served(it) } ?: register(id, spec, closing)
        }

        /** A new server for [id], evicting one first at capacity. Caller holds [lock]. */
        private fun register(id: McpIdentity, spec: McpServerSpec, closing: Closing): McpResult<HostedServer> {
            val ceiling = config.maxServers()
            if (servers.size >= ceiling) evictOne(closing, ceiling)?.let { return it }
            val server = create(spec)
            servers[id] = server
            return McpResult.Served(server)
        }

        private fun reserve(name: String, id: McpIdentity) {
            bindings[name] = id
            reserved[id] = (reserved[id] ?: 0) + 1
        }
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

    /** Null once a victim is gone; the refusal when every server is reserved or streaming. [ceiling] is the
     *  caller's one reading of [McpServerCeiling], so the number in the refusal is the one that refused. */
    private fun evictOne(closing: Closing, ceiling: Int): McpResult.Refused? {
        val victim = servers.keys
            .filterNot { id -> store.namesOf(id).any { reserved(it) || sessions.streaming(it) } }
            .minByOrNull { id -> store.namesOf(id).maxOfOrNull(sessions::lastActivity) ?: 0L }
            ?: return McpResult.Refused(
                "MCP host at capacity ($ceiling servers, all streaming or starting)",
            )
        val last = store.namesOf(victim).maxOfOrNull(sessions::lastActivity) ?: 0L
        val idle = (config.clock.millis() - last) / MILLIS_PER_MINUTE
        store.namesOf(victim).forEach { unbind(it, "evicted after $idle min idle to host a newer server", closing) }
        servers.remove(victim)?.let { closing.add(it, "evicted (no names bound)") }
        return null
    }
}
