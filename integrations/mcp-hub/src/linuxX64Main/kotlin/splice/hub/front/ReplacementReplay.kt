package splice.hub.front

/** Replays persisted routing transitions against the daemon's durable replacement decision. */
internal class ReplacementReplay(
    private val routes: Routes,
    private val holders: Holders,
    private val relay: Relay,
) {
    fun commit(route: Route, drain: HolderDrain, candidate: ReplacementCandidate) {
        routes.replacements.commit(route, drain.socket, drain.generation, candidate) { failure ->
            if (relay.inFlight(drain.socket) != 0) {
                holders.replacements.candidates.candidateBlocked(
                    route,
                    "the old holder acquired work during validation; retry after those exchanges settle",
                )
                return@commit
            }
            val routesMatch = holders.replacements.candidates.canRoute(route, candidate)
            if (failure != null || !routesMatch) {
                holders.replacements.candidates.candidateBlocked(
                    route,
                    failure ?: "the candidate was no longer live when cutover was confirmed",
                )
            } else {
                val moved = routes.replacements.replace(route, drain.socket, drain.generation, candidate)
                if (moved == null) {
                    holders.replacements.candidates.candidateBlocked(
                        route,
                        "the target changed before its candidate could take the settled route",
                    )
                } else {
                    if (!holders.replacements.candidates.routed(route, candidate, moved)) {
                        routes.replacements.restore(moved, drain.socket, drain.generation, candidate)
                    }
                }
            }
        }
    }

    fun rollback(route: Route, drain: HolderDrain, candidate: ReplacementCandidate) {
        routes.replacements.rollback(route, drain.socket, drain.generation, candidate) { failure ->
            if (failure != null) {
                holders.replacements.retirements.rollbackUnknown(route, failure)
            } else if (!routes.replacements.restore(drain.moved, drain.socket, drain.generation, candidate)) {
                holders.replacements.retirements.rollbackUnknown(
                    route,
                    "the restored routes no longer match the dead candidate generation",
                )
            } else {
                holders.replacements.retirements.completed(route)
            }
        }
    }
}
