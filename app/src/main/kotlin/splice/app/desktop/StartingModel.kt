// NEW: door-default (docs/specs/door-default.md, decision 4; desktop-door.md, decision 4). The model a session on the
// door starts on, and the rule that chose it, for both of the door's callers: the `--model` named now, or the one
// chosen the last time while the door still serves it, or the pinned model of the first head in the door. The
// terminal's `claude` adds one step before the last, the pinned model of the first head in its picker (a signed-in
// account's), so a session starts on a model the person can see in /model. And the refusal of a `--model` the door
// does not serve, which both `splice desktop on` and `splice claude door` answer with.
//
// CLAUDE'S OWN ACCOUNTS FIRST (Nov 18, 2026): in that step the head whose command is `claude` goes first while its
// accounts are signed in, so a `claude` session leaves Claude's accounts only when none is in the door. The picker
// lists the other providers first, and a session started or restarted with no model named ran on another provider's
// head while Claude's accounts were added to splice. A Claude session changes provider only once Claude's own
// accounts are all out.
package splice.app.desktop

import splice.core.topology.CLAUDE_COMMAND

/** The model chosen the last time, read at each ask; null when none was. */
internal fun interface LastChosenModel {
    operator fun invoke(): String?
}

internal class StartingModel(
    private val kept: LastChosenModel,
    /** Whether the first head in the picker comes before the first head in the door. */
    private val picker: Boolean,
) {
    /** The model a session starts on in [lineup], and which rule chose it; null with no head in the door. */
    fun of(lineup: DesktopLineup, named: String? = null): Pair<String?, String> {
        val last = kept()?.takeIf { lineup.find(it) != null }
        val signedIn = lineup.heads.filter { it.login }
            .let { heads -> heads.firstOrNull { it.relay.command == CLAUDE_COMMAND } ?: heads.firstOrNull() }
            .takeIf { picker }
        val first = lineup.heads.firstOrNull()
        return when {
            named != null -> named to "the --model you named"
            last != null -> last to "the one chosen the last time"
            signedIn != null -> lineup.pinnedOf(signedIn) to pinnedOf(signedIn, "picker, Claude's own accounts first")
            first != null -> lineup.pinnedOf(first) to pinnedOf(first, "door")
            else -> null to "no head is in the door"
        }
    }

    /** Why `--model` [model] must change nothing: it is not in [lineup]. Null when it is, or none was named. */
    fun refusal(lineup: DesktopLineup, model: String?): String? {
        if (model == null || lineup.find(model) != null) return null
        val serves = lineup.models.joinToString { it.model }.ifEmpty { "no model, with no head in it" }
        return "--model $model is not in the door, which serves $serves"
    }

    /** Why a session starts on [head]'s pinned model: it is the first head in the [where]. */
    private fun pinnedOf(head: DoorHead, where: String): String =
        "the pinned model of ${head.relay.command}, the first head in the $where"
}
