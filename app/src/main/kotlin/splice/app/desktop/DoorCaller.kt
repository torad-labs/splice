// NEW: door-default (docs/specs/door-default.md, decision 3). The door admits two callers, Claude Desktop's Code tab
// and the terminal's `claude`, each by a key of its own, and each with its own switch, its own default model, its own
// window and its own words. The door tells them apart by the key a request presents, and from then on asks the caller.
package splice.app.desktop

import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.record.DesktopLastTurn
import splice.app.desktop.record.DesktopPickerRow
import splice.share.RefusalVoice

internal abstract class DoorCaller(
    /** How the door refuses this caller, in Anthropic's error shape, with its own prefix. */
    val voice: RefusalVoice,
    /** What a turn's row names the door this caller's turns came through. */
    val named: String,
) {
    /** Whether [header], a request's Authorization, presents this caller's key, compared in constant time. */
    abstract fun presents(header: String?): Boolean

    /** Why the door refuses this caller now, with nothing sent upstream, or null while the door is open to it. */
    abstract fun closed(): String?

    /** The selector a session of this caller's runs when it has named none, in [lineup]; null when there is none. */
    abstract fun default(lineup: DesktopLineup): String?

    /** The window this caller's sessions hold, which every count the door reports is a share of; null for none. */
    abstract fun window(): Long?

    /** The window [ask]'s session holds: the caller's own ([window]) unless the request says otherwise. */
    open fun windowOf(ask: DoorAsk): Long? = window()

    /** What a turn is refused with while no provider is in the door. */
    abstract val noHead: String

    /** What a model the door does not serve is refused with, after the model's name. */
    abstract val notInDoor: String

    /** Whether a model of Anthropic's that a request names, in a session on no provider yet, runs on the provider that serves
     *  that model and is refused by name where none does (DoorRouting). False by default: Desktop's picker lists
     *  Anthropic's models alone, and each runs its tier of the default model (desktop-door.md, decision 2). */
    open val runsNamedModels: Boolean = false

    /** A turn on its way to its provider, as the door sends it. Nothing by default. */
    open fun sending(turn: DesktopLastTurn) = Unit

    /** A turn or a count this caller asked, before it is routed. Nothing by default. */
    open fun asked(ask: DoorAsk) = Unit

    /** The door's model list, as Claude Desktop's app mode was answered it ([rows], id and label, in order). Nothing
     *  by default. */
    open fun discovered(rows: List<DesktopPickerRow>) = Unit
}
