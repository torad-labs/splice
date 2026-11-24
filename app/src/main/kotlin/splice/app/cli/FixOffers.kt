// NEW: what the fix session can offer for one finding that sits on a `key = value` line of splice.toml: remove the line,
// set a new value, or move the value where the gateway reads it. Pure facts of the finding, so the session only asks. A
// move is worked out before it is offered, so one that cannot be placed, or would leave the file worse, is never on the
// menu: the person is not offered a move only to have it refused.
package splice.app.cli

import splice.core.config.RetiredKnob
import splice.core.topology.GatewayMove
import splice.core.topology.TopologyFinding

/** The file a move would leave, as lines, or null when it cannot be made (MoveFix). */
internal fun interface MovePlacement {
    operator fun invoke(move: GatewayMove): List<String>?
}

/** [editable] is whether the finding's line holds one `key = value` assignment the session can change. */
internal class FixOffers(finding: TopologyFinding, editable: Boolean, place: MovePlacement) {
    /** A model row's setting cannot be removed: the row's id and context_window are required, so removing either would
     *  only trade this finding for a decode one (a runtime finding names a row as `providers.<p>.models[<id>]`). */
    val removable: Boolean = editable && !finding.path.contains(".models[")

    /** A value can be set on any finding but an unknown key or a retired knob, which have no value to set. */
    val settable: Boolean = editable && hasValue(finding)

    /** The file the finding's move leaves: a move that leaves the line where it is needs no line, any other takes the
     *  value off it. Null when there is no move, or it cannot be made. */
    val moved: List<String>? = finding.move?.takeIf { it.keepsSource || editable }?.let { place(it) }

    /** Where the value can be moved to, when the finding is fixed by moving it and the move can be made. */
    val move: GatewayMove? = finding.move?.takeIf { moved != null }

    /** The answers that change the file, among those on offer: m, d and v. */
    val letters: Set<String> =
        listOfNotNull("m".takeIf { move != null }, "d".takeIf { removable }, "v".takeIf { settable }).toSet()

    private fun hasValue(finding: TopologyFinding): Boolean =
        !finding.message.contains("is not a splice.toml setting") &&
            RetiredKnob.entries.none { finding.message.startsWith(it.retired) }
}
