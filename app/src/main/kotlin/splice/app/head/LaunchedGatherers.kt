// NEW: the Context Getter (docs/specs/harness.md, decision 9). The heads its gatherer is started on, as the launch route
// sees them: each head's launch facts and the daemon's one launch service. The heads' harnesses are built before the
// control plane makes that service, so it is attached once it is made, and a gather asked for before then has no head.
package splice.app.head

import splice.app.control.LaunchHeadAdapter
import splice.app.control.ManagedHead
import splice.harness.GathererHead
import splice.harness.GathererHeads
import splice.launch.recipe.HeadlessLaunch
import splice.launch.recipe.LaunchService

internal class LaunchedGatherers : GathererHeads {
    @Volatile
    private var attached: Attached? = null

    /** The daemon's [heads], read live, and its launch [service]. */
    fun attach(service: LaunchService, heads: Map<String, ManagedHead>) {
        attached = Attached(HeadlessLaunch(service), heads)
    }

    override fun of(head: String): GathererHead? {
        val now = attached ?: return null
        return now.heads[head]?.let { GathererHead(LaunchHeadAdapter.adapt(it), now.headless) }
    }

    private data class Attached(val headless: HeadlessLaunch, val heads: Map<String, ManagedHead>)
}
