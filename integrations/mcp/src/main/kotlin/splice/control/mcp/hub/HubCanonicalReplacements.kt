package splice.control.mcp.hub

import splice.control.mcp.McpResult

internal data class CanonicalReplacement(
    val target: HubTarget,
    val run: HubLaunchSpec,
    val socket: String,
    val tuple: String,
)

internal class HubCanonicalReplacements(
    private val targets: HubTargets,
    private val tuple: HubTuple,
    private val holders: HubHolders,
    private val front: HubFront,
) {
    fun resolve(request: ReplacementStart): McpResult<CanonicalReplacement> {
        val routes = affected(request)
        return when {
            routes == null -> McpResult.Refused("the old holder is not retained by this front route")
            !holders.owns(request.oldSocket, expectedGeneration = request.oldGeneration) ->
                McpResult.Refused("the old holder is not the daemon owner's exact generation")
            else -> selection(routes.map(::target))
        }
    }

    private fun affected(request: ReplacementStart): List<HubRoute>? = front.report()
        ?.routes
        ?.filter { it.socket == request.oldSocket }
        ?.takeIf { routes -> routes.any { it.server == request.server && it.launch == request.launch } }

    private fun target(route: HubRoute): McpResult<Pair<HubTarget, HubLaunchSpec>> {
        val target = targets.target(route.server, route.launch).target
        val run = target?.let(targets::launch)
        return when {
            !route.shared -> McpResult.Refused("one old socket spans mixed or per-session routing targets")
            target == null -> McpResult.Refused("an affected alias no longer has a canonical hub target")
            !target.shared -> McpResult.Refused("one old socket spans mixed shared and per-session targets")
            run is McpResult.Served -> McpResult.Served(target to run.value)
            run is McpResult.Refused -> run
            else -> McpResult.Refused("an affected alias no longer has a canonical hub target")
        }
    }

    private fun selection(
        configured: List<McpResult<Pair<HubTarget, HubLaunchSpec>>>,
    ): McpResult<CanonicalReplacement> {
        val refused = configured.filterIsInstance<McpResult.Refused>().firstOrNull()
        val served = configured.filterIsInstance<McpResult.Served<Pair<HubTarget, HubLaunchSpec>>>()
        return refused ?: selected(served.map { it.value })
    }

    private fun selected(configured: List<Pair<HubTarget, HubLaunchSpec>>): McpResult<CanonicalReplacement> {
        val first = configured.firstOrNull()
        val fingerprint = first?.let { tuple.fingerprint(it.second) }
        return when {
            first == null || fingerprint == null -> McpResult.Refused("the front retained no target for the old holder")
            configured.any { candidate -> !sameTarget(first, fingerprint, candidate) } ->
                McpResult.Refused("affected aliases or launches resolve to mixed canonical replacement tuples")
            else -> McpResult.Served(
                CanonicalReplacement(
                    first.first,
                    first.second,
                    holders.socket(first.first.key).toString(),
                    fingerprint,
                ),
            )
        }
    }

    private fun sameTarget(
        first: Pair<HubTarget, HubLaunchSpec>,
        fingerprint: String,
        candidate: Pair<HubTarget, HubLaunchSpec>,
    ): Boolean = candidate.first.key == first.first.key && tuple.fingerprint(candidate.second) == fingerprint
}
