// NEW: gpt-speed (docs/specs/gpt-speed.md, decisions 1 and 3) — the daemon's speed parts as the launch feature takes
// them: the one record of each session's speed, each head's catalog asked which tiers a model offers, and which heads
// are signed in with ChatGPT. The launch reads it to lay down /fast and /ultrafast, the speed hook route to set them.
package splice.launch.recipe

import splice.core.model.ServiceTier
import splice.core.speed.SessionSpeeds
import splice.core.speed.SpeedCatalog

/** The pinned model of a head signed in with ChatGPT, by its topology key; null for any other head. */
public fun interface ChatGptHeads {
    public fun pinnedModel(headKey: String): String?
}

public class LaunchSpeeds(
    public val sessions: SessionSpeeds,
    public val catalog: SpeedCatalog,
    private val chatGpt: ChatGptHeads,
) {
    /** The tiers the pinned model of the head keyed [headKey] offers, as its catalog entry lists them (possibly none),
     *  when the head is signed in with ChatGPT; null for any other head, which keeps Claude Code's own /fast. Read at
     *  each launch, so a catalog refresh reaches the next one. */
    public fun offered(headKey: String): List<ServiceTier>? =
        chatGpt.pinnedModel(headKey)?.let { catalog.tiersOf(headKey, it) }
}
