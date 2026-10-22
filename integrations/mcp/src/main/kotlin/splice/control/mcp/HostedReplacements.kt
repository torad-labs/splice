package splice.control.mcp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpServerSpec
import splice.client.mcp.McpSharing

internal fun interface HostedServerFactory {
    operator fun invoke(spec: McpServerSpec): HostedServer
}

/** Rebinds a drained launch tuple without ending the client sessions or notification streams. */
internal class HostedReplacements(
    private val store: HostedServerStore,
    private val sharing: McpSharing,
    private val global: GlobalMcpServers,
    private val sessions: McpSessions,
    private val create: HostedServerFactory,
) {
    private val plans = HostedReplacementPlan(store, sharing, global)

    suspend fun admit(name: String, retainedSession: McpSession?): McpAdmission? {
        while (true) {
            val selected = select(name, retainedSession) ?: return null
            val (identity, admission) = selected
            if (!admission.enter()) return null
            val current = synchronized(store.lock) {
                !store.closed && store.admissions[identity] === admission
            }
            if (current) return admission
            admission.leave()
        }
    }

    private fun select(name: String, retainedSession: McpSession?): Pair<McpIdentity, McpAdmission>? {
        // Roots-based reclassification changes future launches, not the sessions already on this child.
        val retained = retainedAdmission(name, retainedSession)
        if (retained != null) return retained
        val spec = sharing.hostedSpec(global(), name) ?: return null
        return synchronized(store.lock) {
            if (store.closed) return@synchronized null
            val incumbent = store.bindings[name]?.let(store.servers::get)
            val identity = if (incumbent?.admission?.paused == true) {
                incumbent.identity
            } else {
                McpIdentity(spec.command, spec.args, spec.env)
            }
            identity to store.admissions.getOrPut(identity) { McpAdmission() }
        }
    }

    private fun retainedAdmission(name: String, session: McpSession?): Pair<McpIdentity, McpAdmission>? =
        synchronized(store.lock) {
            val incumbent = store.bindings[name]?.let(store.servers::get)
            when {
                store.closed -> null
                session != null -> incumbent?.let { it.identity to it.admission }
                incumbent?.admission?.paused == true -> incumbent.identity to incumbent.admission
                else -> null
            }
        }

    fun status(name: String): JsonObject? = synchronized(store.lock) {
        store.bindings[name]?.let(store.servers::get)?.let { it.admission.snapshot(it.pid, it.unsettled) }
    }

    fun pause(name: String): Boolean = synchronized(store.lock) {
        val server = store.bindings[name]?.let(store.servers::get) ?: return@synchronized false
        server.admission.pause()
        true
    }

    fun resume(name: String): Boolean = synchronized(store.lock) {
        store.bindings[name]?.let(store.servers::get)?.admission?.resume() ?: false
    }

    suspend fun replace(name: String): McpResult<Unit> = when (val prepared = plans.prepare(name)) {
        is McpResult.Refused -> prepared
        is McpResult.Served -> perform(prepared.value)
    }

    private suspend fun perform(plan: PreparedReplacement): McpResult<Unit> {
        var candidate: HostedServer? = null
        var committed = false
        var retired = false
        try {
            val next = create(plan.spec)
            candidate = next
            val result = when (val started = next.ensureStarted()) {
                is McpResult.Refused -> started
                is McpResult.Served -> {
                    currentCoroutineContext().ensureActive()
                    accept(plan, next, started.value)
                }
            }
            committed = result is McpResult.Served
            if (committed) {
                plan.old.close("drained replacement")
                retired = true
            }
            return result
        } finally {
            try {
                if (!committed) candidate?.close("replacement was not committed")
            } finally {
                plan.old.admission.finishReplacement()
                if (retired) plan.old.admission.resume()
            }
        }
    }

    private fun accept(plan: PreparedReplacement, next: HostedServer, handshake: JsonObject): McpResult<Unit> = when {
        !compatible(plan.names, handshake) ->
            McpResult.Refused("replacement changes a live session's MCP protocol or capabilities")
        !plans.commit(plan, next) ->
            McpResult.Refused("MCP registry or configuration changed during replacement")
        else -> McpResult.Served(Unit)
    }

    private fun compatible(names: List<String>, handshake: JsonObject): Boolean =
        names.flatMap(sessions::forServer).all { session ->
            session.initResult["protocolVersion"] == handshake["protocolVersion"] &&
                session.initResult["capabilities"] == handshake["capabilities"]
        }
}
