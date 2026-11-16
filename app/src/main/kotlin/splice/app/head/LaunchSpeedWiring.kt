// NEW: gpt-speed (docs/specs/gpt-speed.md, decisions 1 and 3) — what the launch service offers as /fast and /ultrafast
// and what the speed hook sets, wired from the daemon's one record of each session's speed and each head's catalog.
// Its own file so ControlPlane hands it on in one line (concentration).
package splice.app.head

import splice.app.control.ManagedHead
import splice.core.model.HeadDiscoveredModels
import splice.core.speed.SessionSpeeds
import splice.core.speed.SpeedCatalog
import splice.core.topology.AuthKind
import splice.launch.recipe.LaunchSpeeds

internal class LaunchSpeedWiring(private val sessions: SessionSpeeds, rosters: HeadDiscoveredModels) {
    private val catalog = SpeedCatalog(rosters)

    /** The speeds over [heads]: a head signed in with ChatGPT offers its pinned model's tiers; any other, none. */
    fun of(heads: Map<String, ManagedHead>): LaunchSpeeds = LaunchSpeeds(sessions, catalog) { key ->
        val head = heads[key]?.takeIf { it.authSurface.authKind == AuthKind.ChatgptOAuth.wire }
        head?.launchSpec?.models?.pinnedModel
    }
}
