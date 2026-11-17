// NEW: door-default (docs/specs/door-default.md, decision 7). `splice doctor` names the lane the terminal's `claude` runs
// on: the door, or its head, which is deprecated, with the move to the door as its fix. The executable holds the lane
// (its record and the rule that reads it), so it reads it and hands the doctor the reading.
package splice.diagnostics.doctor

import splice.core.topology.Topology
import splice.core.util.EnvReader

/** The lane `claude` is on, in words: [said] what it is, and [fix] what to run about it, when anything. */
public data class ClaudeLaneReading(val onDoor: Boolean, val said: String, val fix: String? = null)

/** The lane of [Topology]'s machine, read under [EnvReader]'s state root; null where nothing reads it. */
public fun interface ClaudeLaneRead {
    public operator fun invoke(topology: Topology, env: EnvReader): ClaudeLaneReading?
}

/** The doctor's check of the lane [read] reads: on the door it passes; on its head it warns, with the move as its fix. */
internal class ClaudeLaneCheck(private val read: ClaudeLaneRead) {
    /** None with no [topology] read, or where nothing reads the lane. */
    fun checks(topology: Topology?, env: EnvReader): List<DoctorCheck> {
        val reading = topology?.let { read(it, env) } ?: return emptyList()
        val status = if (reading.onDoor) CheckStatus.OK else CheckStatus.WARN
        return listOf(DoctorCheck(LANE_CHECK, status, reading.said, reading.fix))
    }
}

// The check that names the lane the terminal's `claude` runs on.
private const val LANE_CHECK = "claude"
