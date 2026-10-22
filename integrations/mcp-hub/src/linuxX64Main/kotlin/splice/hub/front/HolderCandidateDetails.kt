package splice.hub.front

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class HolderCandidateLinks(
    private val known: MutableMap<String, Holder>,
    private val store: HolderReplacementStore,
    private val changed: () -> Unit,
    private val details: HolderCandidateDetails,
) {
    fun verified(holder: Holder, init: JsonObject): String? {
        val drain = candidate(holder) ?: return null
        val reason = details.verify(drain, init)
        if (reason == null) {
            drain.phase = CANDIDATE_READY
            drain.blocker = null
        } else {
            failed(holder, reason)
        }
        return reason
    }

    fun failed(holder: Holder, words: String) {
        candidate(holder)?.let {
            it.phase = details.failedPhase(it.phase)
            it.blocker = words
        }
        holder.settle(words)
        changed()
    }

    fun candidate(holder: Holder): HolderDrain? =
        store.drains.values.firstOrNull { drain ->
            drain.candidate?.socket == holder.socket &&
                drain.candidate?.generation == holder.generation
        }

    fun seed(drain: HolderDrain) {
        drain.candidate?.let { candidate ->
            known.getOrPut(candidate.socket) { Holder(candidate.socket) }.generation = candidate.generation
        }
    }

    fun old(holder: Holder, generation: String?): HolderDrain? =
        store.drains.values.firstOrNull { it.socket == holder.socket && it.generation == generation }
}

internal class HolderCandidateDetails {
    fun capture(route: Route, holder: Holder?): Pair<HolderDrain?, String?> {
        val captured = CapturedHolder(route, holder)
        val refusal = captured.refusal()
        return if (refusal == null) captured.drain() to null else null to refusal
    }

    fun reset(holder: Holder, generation: String) {
        holder.generation = generation
        holder.ready = false
        holder.connecting = false
        holder.init = null
        holder.identity = null
    }

    fun verify(drain: HolderDrain, init: JsonObject): String? = when {
        Rpc.str(init, Rpc.PROTOCOL) != drain.protocolVersion -> "the candidate changes protocolVersion"
        init[CAPABILITIES] != drain.capabilities -> "the candidate changes capabilities"
        else -> null
    }

    fun restore(store: HolderReplacementStore, saved: JsonObject?) {
        stored(saved)?.drain()?.let { restored ->
            store.drains[restored.key()] = restored
        }
    }

    fun failedPhase(phase: String): String = when (phase) {
        ROUTED -> ROLLBACK_PENDING
        RETIRING, RETIRE_BLOCKED, ROLLING_BACK -> RETIRE_UNKNOWN
        ROLLBACK_PENDING, RETIRE_UNKNOWN -> phase
        else -> CANDIDATE_BLOCKED
    }

    private fun stored(saved: JsonObject?): StoredDrain? = saved?.let { document ->
        StoredDrain(
            StoredHolder(
                StoredTarget(
                    Rpc.str(document, SERVER),
                    Rpc.str(document, LAUNCH),
                ),
                Rpc.str(document, SOCKET),
                Rpc.str(document, GENERATION),
                Rpc.str(document, Rpc.PROTOCOL),
                document[CAPABILITIES] as? JsonObject,
            ),
            candidate(document).replacement(),
            moved(document),
            Rpc.str(document, PHASE),
            Rpc.str(document, BLOCKER),
        )
    }

    private fun candidate(document: JsonObject): StoredCandidateRecord = StoredCandidateRecord(
        Rpc.str(document, CANDIDATE_SOCKET),
        Rpc.str(document, CANDIDATE_TUPLE),
        Rpc.str(document, CANDIDATE_GENERATION),
        when (Rpc.str(document, CANDIDATE_SHARED)) {
            "true" -> true
            "false" -> false
            else -> null
        },
    )

    private fun moved(document: JsonObject): Set<String> =
        (document[MOVED] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()
}

private data class CapturedHolder(
    val route: Route,
    val holder: Holder?,
) {
    private val generation = route.generation?.takeIf { it == holder?.generation }
    private val init = holder?.init
    private val protocol = init?.let { Rpc.str(it, Rpc.PROTOCOL) }
    private val capabilities = init?.get(CAPABILITIES) as? JsonObject

    fun refusal(): String? = when {
        holder?.ready != true || holder.link == null -> "${route.server} has no live retained holder for this target"
        generation == null -> "${route.server} has no exact owner generation for this target"
        init == null -> "${route.server} has no retained initialize result"
        protocol == null -> "${route.server} did not report protocolVersion"
        protocol !in Rpc.VERSIONS -> "${route.server} reported unsupported protocolVersion $protocol"
        capabilities == null -> "${route.server} did not report initialize capabilities"
        else -> null
    }

    fun drain(): HolderDrain = HolderDrain(
        route.server,
        route.launch,
        requireNotNull(holder).socket,
        requireNotNull(generation),
        requireNotNull(protocol),
        requireNotNull(capabilities),
    )
}

private data class StoredDrain(
    val holder: StoredHolder,
    val candidate: ReplacementCandidate?,
    val moved: Set<String>,
    val phase: String?,
    val blocker: String?,
) {
    fun drain(): HolderDrain? = holder.drain()?.also {
        it.candidate = candidate
        it.moved = moved
        it.phase = phase ?: PAUSED
        it.blocker = blocker
    }
}

private data class StoredHolder(
    val target: StoredTarget,
    val socket: String?,
    val generation: String?,
    val protocol: String?,
    val capabilities: JsonObject?,
) {
    fun drain(): HolderDrain? = if (complete()) {
        HolderDrain(
            requireNotNull(target.server),
            requireNotNull(target.launch),
            requireNotNull(socket),
            requireNotNull(generation),
            requireNotNull(protocol),
            requireNotNull(capabilities),
        )
    } else {
        null
    }

    private fun complete(): Boolean =
        listOf(target.server, target.launch, socket, generation, protocol, capabilities).none { it == null }
}

private data class StoredTarget(
    val server: String?,
    val launch: String?,
)

private data class StoredCandidateRecord(
    val socket: String?,
    val tuple: String?,
    val generation: String?,
    val shared: Boolean?,
) {
    fun replacement(): ReplacementCandidate? = if (complete()) {
        ReplacementCandidate(
            requireNotNull(socket),
            requireNotNull(tuple),
            requireNotNull(generation),
            requireNotNull(shared),
        )
    } else {
        null
    }

    private fun complete(): Boolean = listOf(socket, tuple, generation, shared).none { it == null }
}
