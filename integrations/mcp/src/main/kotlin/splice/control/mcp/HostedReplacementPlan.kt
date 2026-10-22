package splice.control.mcp

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpServerSpec
import splice.client.mcp.McpSharing

internal data class PreparedReplacement(
    val old: HostedServer,
    val names: List<String>,
    val spec: McpServerSpec,
    val fence: McpReplacementFence? = null,
)

/** Validation and publication use the same registry lock as ordinary server leases. */
internal class HostedReplacementPlan(
    private val store: HostedServerStore,
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
) {
    fun prepare(name: String): McpResult<PreparedReplacement> {
        val configured = global()
        return synchronized(store.lock) {
            when (val read = read(name, configured)) {
                is McpResult.Refused -> read
                is McpResult.Served -> reserve(read.value)
            }
        }
    }

    private fun read(name: String, configured: JsonObject): McpResult<PreparedReplacement> {
        val old = store.bindings[name]?.let(store.servers::get)
            ?: return McpResult.Refused("MCP server is not running")
        val spec = sharing.hostedSpec(configured, name)
            ?: return McpResult.Refused("replacement must remain a shared stdio MCP server")
        val names = store.bindings.filterValues { it == old.identity }.keys.toList()
        val identity = identity(spec)
        return when {
            !available(old, identity) ->
                McpResult.Refused("replacement needs a new, unused launch tuple on a running host")
            !aliasesMatch(names, configured, identity) ->
                McpResult.Refused("every alias must configure the same replacement launch tuple")
            else -> McpResult.Served(PreparedReplacement(old, names, spec))
        }
    }

    private fun available(old: HostedServer, identity: McpIdentity): Boolean {
        if (store.closed) return false
        return identity != old.identity && !store.servers.containsKey(identity)
    }

    private fun reserve(plan: PreparedReplacement): McpResult<PreparedReplacement> {
        val old = plan.old
        val leased = (store.reserved[old.identity] ?: 0) != 0
        if (leased || !old.admission.beginReplacement()) {
            return McpResult.Refused("pause admissions and wait for every on-wire request to settle")
        }
        val fence = old.replacementFence.fence()
        return fence?.let { McpResult.Served(plan.copy(fence = it)) } ?: run {
            old.admission.finishReplacement()
            McpResult.Refused("pause admissions and wait for every on-wire request to settle")
        }
    }

    private fun identity(spec: McpServerSpec): McpIdentity = McpIdentity(spec.command, spec.args, spec.env)

    private fun aliasesMatch(names: List<String>, configured: JsonObject, target: McpIdentity): Boolean =
        names.all { name -> sharing.hostedSpec(configured, name)?.let(::identity) == target }

    /** A candidate is shutdown-owned before it is allowed to launch. */
    fun track(plan: PreparedReplacement, next: HostedServer): Boolean = synchronized(store.lock) {
        if (!owns(plan)) false else store.candidates.add(next)
    }

    fun discard(next: HostedServer) {
        synchronized(store.lock) {
            store.candidates.remove(next)
            if (!store.servers.containsKey(next.identity)) {
                store.admissions.remove(next.identity)
            }
        }
    }

    /** Publishes atomically, then returns the retained session names for post-publication invalidation. */
    fun commit(plan: PreparedReplacement, next: HostedServer): List<String>? {
        val configured = global()
        return synchronized(store.lock) {
            if (!publishable(plan, next, configured)) {
                null
            } else {
                publish(plan, next)
                plan.names
            }
        }
    }

    private fun publishable(plan: PreparedReplacement, next: HostedServer, configured: JsonObject): Boolean {
        val names = store.bindings.filterValues { it == plan.old.identity }.keys.toSet()
        val checks = listOf(
            owns(plan),
            candidateIsReady(next),
            names == plan.names.toSet(),
            aliasesMatch(plan.names, configured, next.identity),
            stillOwned(plan.old, next),
        )
        return checks.all { it }
    }

    private fun owns(plan: PreparedReplacement): Boolean {
        if (store.closed || plan.fence?.held() != true) return false
        return store.servers[plan.old.identity] === plan.old
    }

    private fun candidateIsReady(next: HostedServer): Boolean = next in store.candidates && next.alive

    private fun stillOwned(old: HostedServer, next: HostedServer): Boolean {
        if ((store.reserved[old.identity] ?: 0) != 0) return false
        if (store.servers[old.identity] !== old) return false
        return !store.servers.containsKey(next.identity)
    }

    private fun publish(plan: PreparedReplacement, next: HostedServer) {
        val old = plan.old
        // Waiters keep their admission object, client session objects and notification streams.
        next.admission = old.admission
        store.candidates.remove(next)
        store.admissions.remove(old.identity)
        store.admissions[next.identity] = old.admission
        store.servers.remove(old.identity)
        store.servers[next.identity] = next
        plan.names.forEach { store.bindings[it] = next.identity }
    }
}
