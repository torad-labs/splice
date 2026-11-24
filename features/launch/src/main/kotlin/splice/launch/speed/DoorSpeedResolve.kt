// NEW: resolves `/hooks/speed/door` against the active ChatGPT/Codex provider(s) on the door, kept out of
// SpeedHookRoute so it owns only speed-command evaluation (concentration). Never hijacks Claude Code's built-in
// `/fast` when a door session is on a non-ChatGPT model or has not run a ChatGPT turn yet.
package splice.launch.speed

import splice.client.speed.SpeedCommand
import splice.core.client.DOOR_HOOK_KEY
import splice.core.speed.Speed
import splice.launch.LaunchHeads
import splice.launch.recipe.DoorSpeedTarget
import splice.launch.recipe.LaunchSpeeds

/** Outcome of resolving `/hooks/speed/door` for a session. */
internal sealed class DoorSpeedMatch {
    data class Matched(val catalogKey: String, val model: String) : DoorSpeedMatch()

    /** Let Claude Code handle its own `/fast` untouched (non-ChatGPT model or no ChatGPT turn yet). */
    data object PassThrough : DoorSpeedMatch()

    data class Refused(val reason: String) : DoorSpeedMatch()
}

/** Resolves a `/hooks/speed/door` call against the active ChatGPT/Codex provider(s) served on the door. */
internal class DoorSpeedResolve(
    private val heads: LaunchHeads,
    private val speeds: LaunchSpeeds,
) {
    fun isDoor(key: String): Boolean = key == DOOR_HOOK_KEY

    /** Matches [session] and [command] against active ChatGPT door providers without hijacking Claude `/fast`. */
    fun match(session: String, command: SpeedCommand, unchanged: String): DoorSpeedMatch {
        val targets = resolveTargets()
        if (targets.isEmpty()) {
            return if (command.speed == Speed.FAST) {
                DoorSpeedMatch.PassThrough
            } else {
                DoorSpeedMatch.Refused(
                    "${command.name} works on a ChatGPT provider; none is active on the door, $unchanged",
                )
            }
        }
        val lastModel = speeds.sessions.lastModel(session)
        return if (lastModel != null) {
            matchRecordedModel(targets, lastModel, command)
        } else if (command.speed == Speed.FAST) {
            DoorSpeedMatch.PassThrough
        } else {
            val first = targets.first()
            DoorSpeedMatch.Matched(first.catalogKey, first.pinnedModel)
        }
    }

    private fun matchRecordedModel(
        targets: List<DoorSpeedTarget>,
        lastModel: String,
        command: SpeedCommand,
    ): DoorSpeedMatch {
        val target = targets.firstOrNull { targetMatchesModel(it, lastModel) }
        return when {
            target != null -> DoorSpeedMatch.Matched(target.catalogKey, lastModel)
            command.speed == Speed.FAST -> DoorSpeedMatch.PassThrough
            else -> DoorSpeedMatch.Refused(
                "$lastModel does not offer ${command.speed.title}; ${command.name} is for ChatGPT models. " +
                    "This session's speed is unchanged.",
            )
        }
    }

    private fun targetMatchesModel(target: DoorSpeedTarget, model: String): Boolean =
        target.pinnedModel == model ||
            speeds.catalog.lists(target.catalogKey, model) ||
            speeds.catalog.tiersOf(target.catalogKey, model).isNotEmpty()

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
