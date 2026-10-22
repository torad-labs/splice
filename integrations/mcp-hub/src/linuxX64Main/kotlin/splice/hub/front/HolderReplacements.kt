package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class HolderReplacementStore {
    val drains = HashMap<String, HolderDrain>()

    fun all(): List<HolderDrain> = drains.values.toList()
}

internal data class HolderReplacements(
    private val known: MutableMap<String, Holder>,
    private val changed: () -> Unit,
) {
    private val store = HolderReplacementStore()
    private val details = HolderCandidateDetails()
    val links = HolderCandidateLinks(known, store, changed, details)
    private val preparation = HolderCandidatePreparation(known, details, links)
    val admissions = HolderAdmissions(store)
    val candidates = HolderCandidates(store, changed, preparation, links)
    val retirements = HolderRetirements(store, changed)

    fun all(): List<HolderDrain> = store.all()

    fun reconnectCandidate(candidate: ReplacementCandidate, connect: (Holder) -> Unit): Boolean =
        preparation.reconnect(candidate, connect)

    fun candidateLive(candidate: ReplacementCandidate): Boolean = preparation.current(candidate)
}

internal class HolderCandidates(
    private val store: HolderReplacementStore,
    private val changed: () -> Unit,
    private val preparation: HolderCandidatePreparation,
    private val links: HolderCandidateLinks,
) {

    fun drain(server: String, launch: String): HolderDrain? = store.drains["$server\n$launch"]

    /** Stops new server calls for [route] and captures the contract its replacement must match. */
    fun pause(route: Route, holder: Holder?): String? {
        val existing = store.drains[route.key()]
        if (existing != null) return priorReplacement(existing)
        val captured = details.capture(route, holder)
        val refusal = captured.second
        if (refusal != null) return refusal
        store.drains[route.key()] = requireNotNull(captured.first)
        changed()
        return null
    }

    /** Connects and handshakes the candidate, while the old holder continues holding the bound sessions. */
    fun startCandidate(route: Route, candidate: ReplacementCandidate, connect: (Holder) -> Unit): String? {
        val drain = store.drains[route.key()] ?: return "this target is not paused for replacement"
        val refusal = preparation.refusal(drain, route, candidate)
        return if (refusal != null) {
            refusal
        } else {
            preparation.prepare(drain, candidate, connect, changed)
        }
    }

    fun ready(route: Route? = null): List<HolderDrain> = store.drains.values.filter { drain ->
        drain.phase == CANDIDATE_READY &&
            drain.candidate != null &&
            (route == null || drain === store.drains[route.key()])
    }

    fun claimCandidate(route: Route): ReplacementCandidate? {
        val drain = ready(route).firstOrNull() ?: return null
        drain.phase = COMMITTING
        changed()
        return drain.candidate
    }

    fun candidateBlocked(route: Route, words: String) {
        store.drains[route.key()]?.let { drain ->
            drain.phase = CANDIDATE_BLOCKED
            drain.blocker = words
            changed()
        }
    }

    fun canRoute(route: Route, candidate: ReplacementCandidate): Boolean =
        store.drains[route.key()]?.let { drain ->
            drain.phase == COMMITTING &&
                drain.candidate == candidate &&
                preparation.current(candidate)
        } == true

    fun routed(route: Route, candidate: ReplacementCandidate, moved: Set<String>) {
        store.drains[route.key()]?.takeIf { it.phase == COMMITTING && it.candidate == candidate }?.let { drain ->
            drain.moved = moved
            drain.phase = ROUTED
            drain.blocker = null
            changed()
        }
    }

    fun resume(route: Route): Boolean =
        store.drains[route.key()]?.takeIf { it.phase in resumablePhases }?.let {
            store.drains.remove(route.key())
        }?.also { changed() } != null

    /** The target-specific drain view reports the retained wire ids, not only clients still attached to them. */
    fun status(server: String, launch: String, outstanding: Int, attached: Int): JsonObject? {
        val drain = drain(server, launch) ?: return null
        return buildJsonObject {
            put(
                "target",
                buildJsonObject {
                    put(SERVER, drain.server)
                    put(LAUNCH, drain.launch)
                },
            )
            put("captured_socket", drain.socket)
            put("captured_generation", drain.generation)
            put(Rpc.PROTOCOL, drain.protocolVersion)
            put(CAPABILITIES, drain.capabilities)
            put("admission", if (drain.phase in pausingPhases) "paused" else "open")
            put("wire_outstanding", outstanding)
            put("clients_attached", attached)
            put("settled", outstanding == 0)
            put("replacement", replacementView(drain))
        }
    }

    fun state(): JsonObject = JsonObject(store.drains.mapValues { (_, drain) -> drain.state() })

    fun restore(saved: JsonObject?) {
        saved?.forEach { (_, value) -> details.restore(store, value as? JsonObject) }
    }

    private fun priorReplacement(drain: HolderDrain): String? =
        if (drain.phase in pausingPhases) {
            null
        } else {
            "the previous replacement still needs its old holder retired: ${drain.blocker ?: "no owner result"}"
        }

    private fun replacementView(drain: HolderDrain): JsonObject = buildJsonObject {
        put("state", drain.phase)
        drain.candidate?.let { candidate ->
            put(CANDIDATE_SOCKET, candidate.socket)
            put(CANDIDATE_TUPLE, candidate.tuple)
            put(CANDIDATE_GENERATION, candidate.generation)
            put(CANDIDATE_SHARED, candidate.shared)
        }
        (drain.blocker ?: NO_REPLACEMENT_PRIMITIVE.takeIf { drain.phase == PAUSED })?.let {
            put("blocker", it)
        }
    }
}

internal class HolderAdmissions(private val store: HolderReplacementStore) {
    fun paused(route: Route): Boolean =
        store.drains.values.any { drain -> drain.phase in pausingPhases && captures(drain, route) }

    private fun captures(drain: HolderDrain, route: Route): Boolean = listOf(
        route.key() in drain.moved,
        route.socket == drain.socket,
        drain.candidate?.socket?.let { route.socket == it } == true,
        drain.server == route.server && drain.launch == route.launch,
    ).any { it }
}

private class HolderCandidatePreparation(
    private val known: MutableMap<String, Holder>,
    private val details: HolderCandidateDetails,
    private val links: HolderCandidateLinks,
) {
    fun refusal(drain: HolderDrain, route: Route, candidate: ReplacementCandidate): String? = when {
        drain.phase !in candidateStartPhases -> "this target has already moved to its candidate"
        candidate.shared != route.shared -> "the candidate changes this target's session routing"
        drain.candidate != null && drain.candidate != candidate ->
            "a different candidate already holds this replacement drain"
        else -> null
    }

    fun prepare(
        drain: HolderDrain,
        candidate: ReplacementCandidate,
        connect: (Holder) -> Unit,
        changed: () -> Unit,
    ): String? {
        drain.candidate = candidate
        drain.blocker = null
        val holder = known.getOrPut(candidate.socket) { Holder(candidate.socket) }
        if (holder.generation != candidate.generation) details.reset(holder, candidate.generation)
        if (holder.ready) holder.init?.let { return links.verified(holder, it) }
        if (holder.connecting) return null
        drain.phase = CANDIDATE_STARTING
        holder.connecting = true
        connect(holder)
        changed()
        return null
    }

    fun reconnect(candidate: ReplacementCandidate, connect: (Holder) -> Unit): Boolean {
        val holder = known.getOrPut(candidate.socket) { Holder(candidate.socket) }
        if (holder.generation != candidate.generation) details.reset(holder, candidate.generation)
        if (current(candidate)) return true
        if (holder.connecting) return false
        holder.connecting = true
        connect(holder)
        return current(candidate)
    }

    fun current(candidate: ReplacementCandidate): Boolean = known[candidate.socket]?.let { holder ->
        listOf(holder.ready, holder.link != null, holder.generation == candidate.generation).all { it }
    } == true
}

internal class HolderRetirements(
    private val store: HolderReplacementStore,
    private val changed: () -> Unit,
) {
    fun candidates(): List<HolderDrain> =
        store.drains.values.filter { it.phase == ROUTED && it.candidate != null }

    fun claim(route: Route, candidateLive: (ReplacementCandidate) -> Boolean): HolderDrain? =
        store.drains[route.key()]?.takeIf { drain ->
            drain.phase == ROUTED && drain.candidate?.let(candidateLive) == true
        }?.also { drain ->
            drain.phase = RETIRING
            changed()
        }

    /** Reclaims either an inconclusive retirement or a failed owner call before asking its durable owner again. */
    fun retry(route: Route): HolderDrain? =
        store.drains[route.key()]?.takeIf { drain ->
            drain.phase in retirementRetryPhases && drain.candidate != null
        }?.also { drain ->
            drain.phase = RETIRING
            changed()
        }

    fun unknowns(): List<HolderDrain> =
        store.drains.values.filter { it.phase == RETIRE_UNKNOWN && it.candidate != null }

    fun rollbacks(): List<HolderDrain> =
        store.drains.values.filter { it.phase == ROLLBACK_PENDING && it.candidate != null }

    fun claimRollback(route: Route): HolderDrain? =
        store.drains[route.key()]?.takeIf { drain ->
            drain.phase == ROLLBACK_PENDING &&
                drain.candidate != null &&
                drain.moved.isNotEmpty()
        }?.also { drain ->
            drain.phase = ROLLING_BACK
            changed()
        }

    fun rolledBack(route: Route) {
        if (store.drains.remove(route.key()) != null) changed()
    }

    fun rollbackUnknown(route: Route, words: String) {
        store.drains[route.key()]?.let { drain ->
            drain.phase = RETIRE_UNKNOWN
            drain.blocker = words
            changed()
        }
    }

    fun retired(route: Route) {
        if (store.drains.remove(route.key()) != null) changed()
    }

    fun blocked(route: Route, words: String) {
        store.drains[route.key()]?.takeIf { it.phase == RETIRING }?.let { drain ->
            drain.phase = RETIRE_BLOCKED
            drain.blocker = words
            changed()
        }
    }

    fun pending(route: Route): HolderDrain? =
        store.drains[route.key()]?.takeIf { it.phase == RETIRE_BLOCKED && it.candidate != null }
}

private val candidateStartPhases = setOf(PAUSED, CANDIDATE_STARTING, CANDIDATE_READY)
private val resumablePhases = setOf(PAUSED, CANDIDATE_STARTING, CANDIDATE_READY, CANDIDATE_BLOCKED)
private val retirementRetryPhases = setOf(RETIRE_BLOCKED, RETIRE_UNKNOWN)
