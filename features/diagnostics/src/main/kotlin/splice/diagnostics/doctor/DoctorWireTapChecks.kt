// NEW: V4-173 — the doctor row for a head whose upstream wire tap is ON. The tap is opt-in (operator
// 2026-09-20: "make the upstream request bodies opt-in so our users don't think we're spying on
// them"), and opt-in has to stay VISIBLE: a switch someone flipped for one debugging session and
// forgot is the one that reads as spying a month later. So while `[heads.KEY.overrides] wireTap`
// names a count, every `splice doctor` run says which head keeps how many bodies and where they
// can be read; a head with no count never has a row, because it never has a body.
//
// Reads the topology's OWN declaration (the overrides table), not the daemon: the row must be
// right with the daemon stopped, and the knob is restart-required, so the declaration is what the
// next boot will do. In splice.app.cli.doctor beside DoctorProjectPromptChecks for the same
// concentration reason V4-156 gave.
package splice.diagnostics.doctor

import splice.core.config.Knob
import splice.core.topology.Topology

internal class DoctorWireTapChecks {

    internal fun wireTapChecks(topology: Topology): List<DoctorCheck> =
        topology.heads.mapNotNull { (key, head) ->
            val declared = head.overrides[Knob.WIRE_TAP.key] ?: return@mapNotNull null
            val keep = declared.trim().toLongOrNull()
            when {
                keep == null -> DoctorCheck(
                    "wire-tap:$key",
                    CheckStatus.FAIL,
                    "$key sets overrides.wireTap = \"$declared\", which is not a number",
                    "set wireTap to how many upstream request bodies to keep in memory, or remove it",
                )
                keep <= 0 -> null
                else -> DoctorCheck(
                    "wire-tap:$key",
                    CheckStatus.WARN,
                    "$key keeps its last $keep upstream request bodies in memory (overrides.wireTap): each " +
                        "one carries the whole conversation it was sent for, readable with `splice wire $key` " +
                        "by whoever holds the management key; nothing is written to disk and a restart forgets them",
                    "remove overrides.wireTap from [heads.$key] (and restart) once you are done reading the wire",
                )
            }
        }
}
