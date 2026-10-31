package splice.hub.front

private const val OWNER_PIN_BUSY = "the owner is already reconciling this drain"

/** A local pause becomes actionable only after the daemon excludes that exact generation from eviction. */
internal class HubOwnerPins(
    private val routes: Routes,
    private val holders: Holders,
    private val confirmed: () -> Unit,
) {
    private val active = HashMap<String, String>()
    private val pending = HashSet<String>()
    private val capture = HolderCandidateDetails()

    fun ready(drain: HolderDrain): Boolean = drain.ownerClaim?.let { active[drain.key()] == it } == true

    fun pause(route: Route, done: (String?) -> Unit) {
        val existing = holders.replacements.candidates.drain(route.server, route.launch)
        if (existing != null) return ensure(existing, done)
        val captured = capture.capture(route, holders.links.of(route))
        val drain = captured.first ?: return done(captured.second ?: "the retained holder cannot be drained")
        acquire(drain) { claim, failure ->
            if (claim == null) return@acquire done(failure)
            pending.add(drain.key())
            val refusal = holders.replacements.candidates.pause(route, holders.links.of(route), claim)
            val retained = holders.replacements.candidates.drain(route.server, route.launch)
            pending.remove(drain.key())
            if (refusal != null || !matches(retained, drain)) {
                routes.pins.release(drain, claim) {}
                done(refusal ?: "the retained generation changed before its pause")
            } else {
                ensure(requireNotNull(retained), done)
            }
        }
    }

    private fun matches(retained: HolderDrain?, captured: HolderDrain): Boolean =
        retained?.let { it.socket == captured.socket && it.generation == captured.generation } == true

    fun maintain(drains: List<HolderDrain>) {
        val kept = drains.map(HolderDrain::key).toSet()
        active.keys.retainAll(kept)
        drains.forEach { drain ->
            when {
                drain.phase == UNPINNING -> resume(drain) {}
                !ready(drain) -> ensure(drain) {}
            }
        }
    }

    fun resume(route: Route, done: (String?) -> Unit) {
        if (!pending.add(route.key())) return done(OWNER_PIN_BUSY)
        val drain = holders.replacements.candidates.beginResume(route)
        pending.remove(route.key())
        if (drain == null) done("this replacement cannot safely resume yet") else resume(drain, done)
    }

    private fun resume(drain: HolderDrain, done: (String?) -> Unit) {
        if (!pending.add(drain.key())) return done(OWNER_PIN_BUSY)
        val claim = drain.ownerClaim
        if (claim == null) {
            released(drain, null, done)
        } else {
            routes.pins.release(drain, claim) { failure -> released(drain, failure, done) }
        }
    }

    private fun released(drain: HolderDrain, failure: String?, done: (String?) -> Unit) {
        pending.remove(drain.key())
        if (failure != null) return done(failure)
        active.remove(drain.key())
        val route = routes.byKey(drain.key())
        val removed = route?.let(holders.replacements.candidates::finishResume) == true
        done(if (removed) null else "the owner released its pin but local drain removal could not be persisted")
    }

    private fun ensure(drain: HolderDrain, done: (String?) -> Unit) {
        if (ready(drain)) return done(null)
        if (!pending.add(drain.key())) return done(OWNER_PIN_BUSY)
        routes.pins.acquire(drain) { acquired, failure ->
            if (acquired == null) {
                pending.remove(drain.key())
                done(failure)
            } else {
                adopted(drain, acquired, done)
            }
        }
    }

    private fun adopted(drain: HolderDrain, claim: String, done: (String?) -> Unit) {
        val route = routes.byKey(drain.key())
        if (route?.let { holders.replacements.candidates.adoptClaim(it, claim) } != true) {
            pending.remove(drain.key())
            routes.pins.release(drain, claim) {}
            done("the owner claim could not be retained with the drain")
        } else {
            confirm(drain, claim, done)
        }
    }

    private fun confirm(drain: HolderDrain, claim: String, done: (String?) -> Unit) {
        routes.pins.confirm(drain, claim) { failure ->
            pending.remove(drain.key())
            val retained = holders.replacements.candidates.drain(drain.server, drain.launch)
            val current = listOf(retained === drain, drain.ownerClaim == claim, drain.phase != UNPINNING).all { it }
            if (failure == null && current) {
                active[drain.key()] = claim
                confirmed()
                done(null)
            } else {
                done(failure ?: "the drain changed while its owner claim was being confirmed")
            }
        }
    }

    private fun acquire(drain: HolderDrain, done: (String?, String?) -> Unit) {
        if (!pending.add(drain.key())) return done(null, OWNER_PIN_BUSY)
        routes.pins.acquire(drain) { claim, failure ->
            pending.remove(drain.key())
            done(claim, failure)
        }
    }
}
