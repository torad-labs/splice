package splice.control.mcp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpServerSpec
import splice.client.mcp.McpSharing

private const val TOOLS_LIST_CHANGED = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""

internal fun interface HostedServerFactory {
    operator fun invoke(spec: McpServerSpec): HostedServer
}

private fun interface ReplacementAcceptance {
    suspend fun accept(plan: PreparedReplacement, next: HostedServer, handshake: JsonObject): McpResult<Unit>
}

/** Owns one candidate's launch, publication outcome and the exact rollback that follows it. */
private class ReplacementAttempt(
    private val plan: PreparedReplacement,
    private val plans: HostedReplacementPlan,
    private val create: HostedServerFactory,
    private val accept: ReplacementAcceptance,
) {
    private var candidate: HostedServer? = null
    private var committed = false

    suspend fun run(): McpResult<Unit> {
        val next = create(plan.spec)
        candidate = next
        if (!plans.track(plan, next)) return McpResult.Refused("MCP host stopped during replacement")
        val result = candidateStart(next)
        committed = result is McpResult.Served
        if (committed) plan.old.close("drained replacement")
        return result
    }

    fun finish() {
        if (!committed) rollback()
        plan.old.admission.finishReplacement()
        if (committed) plan.old.admission.resume()
    }

    private suspend fun candidateStart(next: HostedServer): McpResult<Unit> =
        when (val started = next.ensureStarted()) {
            is McpResult.Refused -> started
            is McpResult.Served -> {
                currentCoroutineContext().ensureActive()
                accept(plan, next, started.value)
            }
        }

    private fun rollback() {
        candidate?.let {
            plans.discard(it)
            it.close("replacement was not committed")
        }
        // The old admission stays explicitly drained after refusal. Only management's resume may reopen it.
        plan.fence?.reopen()
    }
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
        val attempt = ReplacementAttempt(plan, plans, create, ReplacementAcceptance(::accept))
        return try {
            attempt.run()
        } finally {
            attempt.finish()
        }
    }

    private fun accept(plan: PreparedReplacement, next: HostedServer, handshake: JsonObject): McpResult<Unit> {
        if (!compatible(plan.names, handshake)) {
            return McpResult.Refused("replacement changes a live session's MCP protocol or capabilities")
        }
        val retained = plans.commit(plan, next)
            ?: return McpResult.Refused("MCP registry or configuration changed during replacement")
        retained.forEach { sessions.fanOut(it, TOOLS_LIST_CHANGED) }
        return McpResult.Served(Unit)
    }

    private fun compatible(names: List<String>, handshake: JsonObject): Boolean =
        names.flatMap(sessions::forServer).all { session ->
            session.initResult["protocolVersion"] == handshake["protocolVersion"] &&
                session.initResult["capabilities"] == handshake["capabilities"]
        }
}
