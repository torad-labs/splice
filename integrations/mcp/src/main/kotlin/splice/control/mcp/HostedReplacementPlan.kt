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

/** Checks and mutations that require the registry lock while a replacement owns its old generation. */
private class ReplacementOwnership(private val store: HostedServerStore) {
    fun available(old: HostedServer, identity: McpIdentity): Boolean {
        if (store.closed || identity == old.identity) return false
        return !tupleClaimed(identity)
    }

    fun track(plan: PreparedReplacement, next: HostedServer): Boolean {
        if (!owns(plan) || tupleClaimed(next.identity)) {
            return false
        }
        store.candidates[next.identity] = next
        return true
    }

    fun owns(plan: PreparedReplacement): Boolean {
        if (store.closed || plan.fence?.held() != true) return false
        return store.servers[plan.old.identity] === plan.old
    }

    fun candidateIsReady(next: HostedServer): Boolean =
        store.candidates[next.identity] === next && next.alive

    fun stillOwned(old: HostedServer, next: HostedServer): Boolean {
        if ((store.reserved[old.identity] ?: 0) != 0) return false
        if (store.servers[old.identity] !== old) return false
        return !store.servers.containsKey(next.identity)
    }

    private fun tupleClaimed(identity: McpIdentity): Boolean =
        store.servers.containsKey(identity) || store.candidates.containsKey(identity)
}

/** Validation and publication use the same registry lock as ordinary server leases. */
internal class HostedReplacementPlan(
    private val store: HostedServerStore,
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
) {
    private val ownership = ReplacementOwnership(store)
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
            !ownership.available(old, identity) ->
                McpResult.Refused("replacement needs a new, unused launch tuple on a running host")
            !aliasesMatch(names, configured, identity) ->
                McpResult.Refused("every alias must configure the same replacement launch tuple")
            else -> McpResult.Served(PreparedReplacement(old, names, spec))
        }
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

    /** A candidate is shutdown-owned before it is allowed to launch and exclusively reserves its tuple. */
    fun track(plan: PreparedReplacement, next: HostedServer): Boolean = synchronized(store.lock) {
        ownership.track(plan, next)
    }

    fun discard(next: HostedServer) {
        synchronized(store.lock) {
            if (store.candidates[next.identity] === next) store.candidates.remove(next.identity)
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
            ownership.owns(plan),
            ownership.candidateIsReady(next),
            names == plan.names.toSet(),
            aliasesMatch(plan.names, configured, next.identity),
            ownership.stillOwned(plan.old, next),
        )
        return checks.all { it }
    }

    private fun publish(plan: PreparedReplacement, next: HostedServer) {
        val old = plan.old
        // Waiters keep their admission object, client session objects and notification streams.
        next.admission = old.admission
        store.candidates.remove(next.identity)
        store.admissions.remove(old.identity)
        store.admissions[next.identity] = old.admission
        store.servers.remove(old.identity)
        store.servers[next.identity] = next
        plan.names.forEach { store.bindings[it] = next.identity }
    }
}
