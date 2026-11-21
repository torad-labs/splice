// NEW: what the fix session can offer for one finding that sits on a `key = value` line of splice.toml: remove the line,
// set a new value, or move the value where the gateway reads it. Pure facts of the finding, so the session only asks.
package splice.app.cli

import splice.core.config.RetiredKnob
import splice.core.topology.GatewayMove
import splice.core.topology.TopologyFinding

/** [editable] is whether the finding's line holds one `key = value` assignment the session can change. */
internal class FixOffers(finding: TopologyFinding, editable: Boolean) {
    /** A model row's setting cannot be removed: the row's id and context_window are required, so removing either would
     *  only trade this finding for a decode one (a runtime finding names a row as `providers.<p>.models[<id>]`). */
    val removable: Boolean = editable && !finding.path.contains(".models[")

    /** A value can be set on any finding but an unknown key or a retired knob, which have no value to set. */
    val settable: Boolean = editable && hasValue(finding)

    /** Where the value can be moved to, when the finding is fixed by moving it: a move that leaves the line where it is
     *  needs no line, any other takes the value off it. */
    val move: GatewayMove? = finding.move?.takeIf { it.keepsSource || editable }

    /** The answers that change the file, among those on offer: m, d and v. */
    val letters: Set<String> =
        listOfNotNull("m".takeIf { move != null }, "d".takeIf { removable }, "v".takeIf { settable }).toSet()

    private fun hasValue(finding: TopologyFinding): Boolean =
        !finding.message.contains("is not a splice.toml setting") &&
            RetiredKnob.entries.none { finding.message.startsWith(it.retired) }
}
