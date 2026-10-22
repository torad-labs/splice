package splice.hub.front

internal class HolderReplacementStore {
    val drains = HashMap<String, HolderDrain>()

    fun all(): List<HolderDrain> = drains.values.toList()
}

internal data class HolderReplacements(
    private val known: MutableMap<String, Holder>,
    private val changed: () -> Unit,
    private val checkpoint: () -> Boolean,
) {
    private val store = HolderReplacementStore()
    private val details = HolderCandidateDetails()
    val links = HolderCandidateLinks(known, store, changed, details)
    private val preparation = HolderCandidatePreparation(known, details, links, checkpoint)
    val admissions = HolderAdmissions(store)
    val views = HolderDrainViews(store, details)
    val candidates = HolderCandidates(store, changed, checkpoint, preparation, links, details)
    val retirements = HolderRetirements(store, changed, checkpoint, preparation)

    fun all(): List<HolderDrain> = store.all()

    fun reconnectCandidate(candidate: ReplacementCandidate, connect: (Holder) -> Unit): Boolean =
        preparation.reconnect(candidate, connect)

    fun candidateLive(candidate: ReplacementCandidate): Boolean = preparation.current(candidate)

    fun fence(drain: HolderDrain): Boolean {
        val holder = known.getOrPut(drain.socket) { Holder(drain.socket) }
        if (holder.generation != null && holder.generation != drain.generation) return false
        val priorGeneration = holder.generation
        val priorFence = holder.retirementFence
        holder.generation = drain.generation
        holder.retirementFence = drain.generation
        if (checkpoint()) {
            changed()
            return true
        }
        holder.generation = priorGeneration
        holder.retirementFence = priorFence
        return false
    }

    fun unfence(drain: HolderDrain): Boolean {
        val holder = known[drain.socket] ?: return true
        val held = holder.generation == drain.generation && holder.retirementFence == drain.generation
        return if (!held) {
            true
        } else {
            holder.retirementFence = null
            if (checkpoint()) {
                changed()
                true
            } else {
                holder.retirementFence = drain.generation
                false
            }
        }
    }
}

internal class HolderCandidates(
    private val store: HolderReplacementStore,
    private val changed: () -> Unit,
    private val checkpoint: () -> Boolean,
    private val preparation: HolderCandidatePreparation,
    private val links: HolderCandidateLinks,
    private val details: HolderCandidateDetails,
) {

    fun drain(server: String, launch: String): HolderDrain? = store.drains["$server\n$launch"]

    /** Stops new server calls only after the old-generation owner claim and drain are durable together. */
    fun pause(route: Route, holder: Holder?, ownerClaim: String?): String? {
        val existing = store.drains[route.key()]
        if (existing != null) return priorReplacement(existing)
        val captured = details.capture(route, holder)
        val refusal = captured.second
        if (refusal != null) return refusal
        val drain = requireNotNull(captured.first).also { it.ownerClaim = ownerClaim }
        store.drains[route.key()] = drain
        return if (!checkpoint()) {
            store.drains.remove(route.key())
            "the front could not durably retain the replacement drain"
        } else {
            changed()
            null
        }
    }

    /** Attaches the pin acquired before a legacy drain was restored, before any later transition can use it. */
    fun adoptClaim(route: Route, claim: String): Boolean {
        val drain = store.drains[route.key()] ?: return false
        val prior = drain.ownerClaim
        if (prior == claim) return true
        drain.ownerClaim = claim
        return if (checkpoint()) {
            changed()
            true
        } else {
            drain.ownerClaim = prior
            false
        }
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
        val prior = drain.phase
        drain.phase = COMMITTING
        if (!checkpoint()) {
            drain.phase = prior
            return null
        }
        changed()
        return drain.candidate
    }

    fun candidateBlocked(route: Route, words: String) {
        store.drains[route.key()]?.let { drain ->
            val priorPhase = drain.phase
            val priorBlocker = drain.blocker
            drain.phase = CANDIDATE_BLOCKED
            drain.blocker = words
            if (checkpoint()) {
                changed()
            } else {
                drain.phase = priorPhase
                drain.blocker = priorBlocker
            }
        }
    }

    fun canRoute(route: Route, candidate: ReplacementCandidate): Boolean =
        store.drains[route.key()]?.let { drain ->
            drain.phase == COMMITTING &&
                drain.candidate == candidate &&
                preparation.current(candidate)
        } == true

    fun routed(route: Route, candidate: ReplacementCandidate, moved: Set<String>): Boolean {
        val drain = store.drains[route.key()]?.takeIf { it.phase == COMMITTING && it.candidate == candidate }
            ?: return false
        val priorMoved = drain.moved
        val priorBlocker = drain.blocker
        drain.moved = moved
        drain.phase = ROUTED
        drain.blocker = null
        if (!checkpoint()) {
            drain.moved = priorMoved
            drain.phase = COMMITTING
            drain.blocker = priorBlocker
            return false
        }
        changed()
        return true
    }

    /** Keeps admissions paused while the persisted old-generation pin is released asynchronously. */
    fun beginResume(route: Route): HolderDrain? {
        val drain = store.drains[route.key()]?.takeIf { it.phase in resumablePhases } ?: return null
        val prior = drain.phase
        drain.phase = UNPINNING
        if (checkpoint()) {
            changed()
            return drain
        }
        drain.phase = prior
        return null
    }

    /** Deletes a released drain only after the deletion itself is durable. */
    fun finishResume(route: Route): Boolean {
        val drain = store.drains[route.key()]?.takeIf { it.phase == UNPINNING } ?: return false
        store.drains.remove(route.key())
        if (checkpoint()) {
            changed()
            return true
        }
        store.drains[route.key()] = drain
        return false
    }

    private fun priorReplacement(drain: HolderDrain): String? =
        if (drain.phase in pausingPhases) {
            null
        } else {
            "the previous replacement still needs its old holder retired: ${drain.blocker ?: "no owner result"}"
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

internal class HolderCandidatePreparation(
    private val known: MutableMap<String, Holder>,
    private val details: HolderCandidateDetails,
    private val links: HolderCandidateLinks,
    private val checkpoint: () -> Boolean,
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
        val priorCandidate = drain.candidate
        val priorBlocker = drain.blocker
        val priorPhase = drain.phase
        drain.candidate = candidate
        drain.blocker = null
        val holder = known.getOrPut(candidate.socket) { Holder(candidate.socket) }
        if (holder.generation != candidate.generation) details.reset(holder, candidate.generation)
        if (holder.ready) holder.init?.let { return links.verified(holder, it) }
        if (holder.connecting) return null
        drain.phase = CANDIDATE_STARTING
        return if (!checkpoint()) {
            drain.candidate = priorCandidate
            drain.blocker = priorBlocker
            drain.phase = priorPhase
            "the front could not durably retain the candidate start"
        } else {
            holder.connecting = true
            connect(holder)
            changed()
            null
        }
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
        listOf(
            holder.ready,
            holder.link != null,
            holder.generation == candidate.generation,
            holder.attestedGeneration == candidate.generation,
        ).all { it }
    } == true
}

internal class HolderRetirements(
    private val store: HolderReplacementStore,
    private val changed: () -> Unit,
    private val checkpoint: () -> Boolean,
    private val preparation: HolderCandidatePreparation,
) {
    fun candidates(): List<HolderDrain> =
        store.drains.values.filter { it.phase == ROUTED && it.candidate != null }

    fun claim(route: Route, candidateLive: (ReplacementCandidate) -> Boolean): HolderDrain? {
        val drain = store.drains[route.key()]?.takeIf { candidate ->
            candidate.phase == ROUTED &&
                candidate.ownerClaim != null &&
                candidate.candidate?.let(candidateLive) == true
        } ?: return null
        return claimed(drain)
    }

    /** Re-enters an owner-confirmed retirement only after the caller rechecked the old generation's ledger. */
    fun retry(route: Route): HolderDrain? =
        store.drains[route.key()]
            ?.takeIf { drain ->
                listOf(
                    drain.phase in retirementRetryPhases,
                    drain.ownerClaim != null,
                    drain.candidate != null,
                ).all { it }
            }
            ?.let(::claimed)

    private fun claimed(drain: HolderDrain): HolderDrain? {
        val prior = drain.phase
        drain.phase = RETIRING
        if (!checkpoint()) {
            drain.phase = prior
            return null
        }
        changed()
        return drain
    }

    fun unknowns(): List<HolderDrain> =
        store.drains.values.filter { it.phase == RETIRE_UNKNOWN && it.candidate != null }

    fun rollbacks(): List<HolderDrain> =
        store.drains.values.filter { it.phase == ROLLBACK_PENDING && it.candidate != null }

    fun claimRollback(route: Route): HolderDrain? {
        val drain = store.drains[route.key()]?.takeIf {
            it.phase == ROLLBACK_PENDING && it.candidate != null && it.moved.isNotEmpty()
        } ?: return null
        drain.phase = ROLLING_BACK
        if (!checkpoint()) {
            drain.phase = ROLLBACK_PENDING
            return null
        }
        changed()
        return drain
    }

    fun completed(route: Route) = remove(route)

    /** A rollback whose outcome is unknown remains distinct from a retirement that the daemon owns. */
    fun rollbackUnknown(route: Route, words: String) {
        store.drains[route.key()]?.let { drain ->
            transition(drain, ROLLBACK_UNKNOWN, words)
        }
    }

    fun blocked(route: Route, words: String) {
        store.drains[route.key()]?.takeIf { it.phase == RETIRING }?.let { drain ->
            transition(drain, RETIRE_BLOCKED, words)
        }
    }

    /** Exact exit evidence releases old loss and a rollback whose candidate was confirmed gone. */
    fun oldExited(socket: String, generation: String) {
        store.drains.values
            .filter { it.socket == socket && it.generation == generation && it.phase == OLD_HOLDER_LOST }
            .forEach { drain ->
                val candidate = drain.candidate
                val phase = if (candidate?.let(preparation::current) == true) CANDIDATE_READY else CANDIDATE_STARTING
                transition(drain, if (candidate == null) PAUSED else phase, null)
            }
        store.drains.values
            .filter { drain ->
                drain.candidate?.socket == socket &&
                    drain.candidate?.generation == generation &&
                    drain.phase == ROLLBACK_UNKNOWN
            }
            .forEach { drain -> transition(drain, ROLLBACK_PENDING, null) }
    }

    private fun remove(route: Route) {
        val removed = store.drains.remove(route.key()) ?: return
        if (checkpoint()) {
            changed()
        } else {
            store.drains[route.key()] = removed
        }
    }

    private fun transition(drain: HolderDrain, phase: String, blocker: String?) {
        val priorPhase = drain.phase
        val priorBlocker = drain.blocker
        drain.phase = phase
        drain.blocker = blocker
        if (checkpoint()) {
            changed()
        } else {
            drain.phase = priorPhase
            drain.blocker = priorBlocker
        }
    }

    fun pending(route: Route): HolderDrain? =
        store.drains[route.key()]?.takeIf { it.phase == RETIRE_BLOCKED && it.candidate != null }
}

private val candidateStartPhases = setOf(PAUSED, CANDIDATE_STARTING, CANDIDATE_READY)
private val resumablePhases = setOf(PAUSED, CANDIDATE_STARTING, CANDIDATE_READY, CANDIDATE_BLOCKED, UNPINNING)
private val retirementRetryPhases = setOf(RETIRE_BLOCKED, RETIRE_UNKNOWN)
