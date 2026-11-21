// NEW: door-default (docs/specs/door-default.md, decision 7). `splice doctor` names the lane the terminal's `claude` runs
// on: the door, or its head, which carries the door's models (decision 9). Neither lane is deprecated, so either one
// passes. The executable holds the lane (its record and the rule that reads it), so it reads it and hands the doctor the
// reading.
//
// desktop-full (docs/specs/desktop-full.md, df-diagnostics): and the door's other caller, Claude Desktop: which mode it
// is in, read the same way. Every mode passes; app mode warns while Desktop has not been reopened since splice changed
// its configuration, or since the picker changed, since Desktop reads both only at launch.
package splice.diagnostics.doctor

import splice.core.topology.Topology
import splice.core.util.EnvReader

/** The lane `claude` is on, in words: [said] what it is, and [fix] what to run about it, when anything; and Claude
 *  Desktop as the door reads it ([desktop]), where something reads it. */
public data class ClaudeLaneReading(
    val onDoor: Boolean,
    val said: String,
    val fix: String? = null,
    val desktop: DesktopReading? = null,
)

/** Claude Desktop in words: [said] its mode, [warn] whether it must be reopened to read what splice changed, and [fix]
 *  what to do about it. */
public data class DesktopReading(val said: String, val warn: Boolean = false, val fix: String? = null)

/** The lane of [Topology]'s machine, read under [EnvReader]'s state root; null where nothing reads it. */
public fun interface ClaudeLaneRead {
    public operator fun invoke(topology: Topology, env: EnvReader): ClaudeLaneReading?
}

/** The doctor's checks of the lane [read] reads, which passes on either lane and says which one it is, and of Claude
 *  Desktop where the reading carries it. */
internal class ClaudeLaneCheck(private val read: ClaudeLaneRead) {
    /** None with no [topology] read, or where nothing reads the lane. */
    fun checks(topology: Topology?, env: EnvReader): List<DoctorCheck> {
        val reading = topology?.let { read(it, env) } ?: return emptyList()
        val desktop = reading.desktop?.let {
            DoctorCheck(DESKTOP_CHECK, if (it.warn) CheckStatus.WARN else CheckStatus.OK, it.said, it.fix)
        }
        return listOfNotNull(DoctorCheck(LANE_CHECK, CheckStatus.OK, reading.said, reading.fix), desktop)
    }
}

// The check that names the lane the terminal's `claude` runs on.
private const val LANE_CHECK = "claude"

// The check that names Claude Desktop's mode.
private const val DESKTOP_CHECK = "desktop"
