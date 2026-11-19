// NEW: the Context Getter (docs/specs/harness.md, decision 9). What the gatherer is started from, as the daemon hands it
// over once its heads and its launch service are up: a head's launch facts, and the launch a session splice starts
// itself on that head gets.
package splice.harness

import splice.launch.LaunchHead
import splice.launch.recipe.HeadlessLaunch

/** A head the gatherer can be started on, and how splice launches a session of it. */
public data class GathererHead(val launch: LaunchHead, val headless: HeadlessLaunch)

/** The heads a gatherer can be started on, by key: null for one splice cannot launch a session of (yet). */
public fun interface GathererHeads {
    public fun of(head: String): GathererHead?
}
