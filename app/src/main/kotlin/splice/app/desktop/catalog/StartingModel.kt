// NEW: door-default (docs/specs/door-default.md, decision 4; desktop-door.md, decision 4). The model a session on the
// door starts on, and the rule that chose it, for both of the door's callers: the `--model` named now, or the one
// chosen the last time while the door still serves it, or the pinned model of the first provider in the door. The
// terminal's `claude` adds one step before the last, the pinned model of the first provider in its picker (a signed-in
// account's), so a session starts on a model the person can see in /model. And the refusal of a `--model` the door
// does not serve, which both `splice desktop on` and `splice claude door` answer with.
//
// CLAUDE'S OWN ACCOUNTS FIRST (Nov 18, 2026): in that step the provider that forwards the caller's Claude login goes
// first while its accounts are signed in, so a `claude` session leaves Claude's accounts only when none is in the door.
// The picker lists the other providers first, and a session started or restarted with no model named ran on another
// provider while Claude's accounts were added to splice. A Claude session changes provider only once Claude's own
// accounts are all out.
package splice.app.desktop.catalog

import splice.app.provider.ServedProvider

/** The model chosen the last time, read at each ask; null when none was. */
internal fun interface LastChosenModel {
    operator fun invoke(): String?
}

internal class StartingModel(
    private val kept: LastChosenModel,
    /** Whether the first provider in the picker comes before the first provider in the door. */
    private val picker: Boolean,
) {
    /** The model a session starts on in [lineup], and which rule chose it; null with no provider in the door. */
    fun of(lineup: DesktopLineup, named: String? = null): Pair<String?, String> {
        val last = kept()?.takeIf { lineup.find(it) != null }
        val signedIn = lineup.providers.filter { it.usesLogin }
            .let { found -> found.firstOrNull { it.forwardsClientAuth } ?: found.firstOrNull() }
            .takeIf { picker }
        val first = lineup.providers.firstOrNull()
        return when {
            named != null -> named to "the --model you named"
            last != null -> last to "the one chosen the last time"
            signedIn != null -> lineup.pinnedOf(signedIn) to pinnedOf(signedIn, "picker, Claude's own accounts first")
            first != null -> lineup.pinnedOf(first) to pinnedOf(first, "door")
            else -> null to "no provider is in the door"
        }
    }

    /** Why `--model` [model] must change nothing: it is not in [lineup]. Null when it is, or none was named. */
    fun refusal(lineup: DesktopLineup, model: String?): String? {
        if (model == null || lineup.find(model) != null) return null
        val serves = lineup.models.joinToString { it.model }.ifEmpty { "no model, with no provider in it" }
        return "--model $model is not in the door, which serves $serves"
    }

    /** Why a session starts on [provider]'s pinned model: it is the first provider in the [where]. */
    private fun pinnedOf(provider: ServedProvider, where: String): String =
        "the pinned model of ${provider.key}, the first provider in the $where"
}
