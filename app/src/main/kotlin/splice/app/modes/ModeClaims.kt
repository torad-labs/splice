// NEW: the paths the modes' routes claim (modes/README.md, "The contract"), checked when the control plane's routing is
// built, against splice's own routes as that routing holds them, never a list kept beside it: two modes declaring one
// method and path, or a mode declaring a path one of splice's own routes answers, refuses the daemon, every claim at
// once, each finding naming the mode and the path.
package splice.app.modes

import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import splice.core.topology.TopologyFinding
import splice.modes.api.FoundMode

internal class ModeClaims(private val own: RoutingNode) {
    /** The finding of each route of [modes] whose method and path is taken, by splice or by a mode before it. */
    fun findings(modes: List<FoundMode>): List<TopologyFinding> {
        val splice = routes()
        val claimed = HashMap<String, String>()
        return modes.flatMap { mode ->
            mode.handler.routes.mapNotNull { route ->
                val method = route.method.uppercase()
                val claim = "$method ${route.path}"
                val segments = route.path.split('/').filter(String::isNotEmpty)
                val said = when {
                    splice.any { it.answers(method, segments) } -> "$claim is splice's own"
                    else -> claimed.putIfAbsent(claim, mode.name)?.let { first -> "$claim is also mode $first's" }
                }
                said?.let { TopologyFinding("mode ${mode.name}", it, origin = TopologyFinding.Origin.Mode(mode.name)) }
            }
        }
    }

    /** Every route the routing answers, each as its method and its path's segments, a parameter matching any. */
    private fun routes(): List<OwnRoute> = own.getAllRoutes().mapNotNull { leaf ->
        val chain = generateSequence(leaf) { it.parent }.toList().asReversed()
        val method = chain.firstNotNullOfOrNull { (it.selector as? HttpMethodRouteSelector)?.method?.value }
        method?.let { OwnRoute(it, chain.flatMap { node -> segment(node) }) }
    }

    private fun segment(node: RoutingNode): List<String?> = when (val selector = node.selector) {
        is PathSegmentConstantRouteSelector -> listOf(selector.value)
        is HttpMethodRouteSelector -> emptyList()
        else -> selectorSegment(selector.toString())
    }

    /** A selector that is not a constant segment: a `{parameter}` matches any one segment, and one that adds no
     *  segment (the root, a header or a trailing slash) adds none. */
    private fun selectorSegment(text: String): List<String?> = when {
        text.startsWith("{") -> listOf(null)
        else -> emptyList()
    }

    /** One of splice's own routes: its method, and its segments, null for a parameter. */
    private data class OwnRoute(val method: String, val segments: List<String?>) {
        fun answers(method: String, path: List<String>): Boolean = method == this.method &&
            path.size == segments.size && segments.zip(path).all { (own, asked) -> own == null || own == asked }
    }
}
