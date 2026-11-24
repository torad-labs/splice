// NEW: resolves `/hooks/speed/door` against the active ChatGPT/Codex provider(s) on the door, kept out of
// SpeedHookRoute so it owns only speed-command evaluation (concentration).
package splice.launch.speed

import splice.core.client.DOOR_HOOK_KEY
import splice.launch.LaunchHeads
import splice.launch.recipe.DoorSpeedTarget
import splice.launch.recipe.LaunchSpeeds

/** Resolved catalog key and model for a door `/fast` or `/ultrafast` invocation. */
internal data class DoorSpeedChoice(val catalogKey: String, val model: String)

/** Resolves a `/hooks/speed/door` call against the active ChatGPT/Codex provider(s) served on the door. */
internal class DoorSpeedResolve(
    private val heads: LaunchHeads,
    private val speeds: LaunchSpeeds,
) {
    fun isDoor(key: String): Boolean = key == DOOR_HOOK_KEY

    /** Chooses the active ChatGPT catalog key and model for [session], or null when none is active on the door. */
    fun choose(session: String): DoorSpeedChoice? {
        val targets = resolveTargets()
        if (targets.isEmpty()) return null
        val lastModel = speeds.sessions.lastModel(session)
        val chosen = if (lastModel != null) {
            targets.firstOrNull { speeds.catalog.tiersOf(it.catalogKey, lastModel).isNotEmpty() } ?: targets.first()
        } else {
            targets.first()
        }
        return DoorSpeedChoice(chosen.catalogKey, lastModel ?: chosen.pinnedModel)
    }

    private fun resolveTargets(): List<DoorSpeedTarget> {
        val fromDoor = speeds.doorTargets()
        if (fromDoor.isNotEmpty()) return fromDoor
        return heads.all().mapNotNull { managed ->
            val key = managed.head.key
            val pinned = managed.spec?.models?.pinnedModel?.takeIf(String::isNotBlank)
            if (pinned != null && speeds.offered(key) != null) DoorSpeedTarget(key, pinned) else null
        }
    }
}
