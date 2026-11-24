// NEW: failure-handling slice 4. A retired knob set where splice.toml cannot show it: an environment alias, or a value
// splice saved in its state folder's config.json. Both win over splice.toml, so once the knob left the schema either
// would be ignored in silence (final-design.md section 2). Boot says them, and the fix session reads each one's origin
// for its move: it clears a saved value, and names a variable to unset. upgrade-compat (Nov 23, 2026): they are warnings,
// never a refusal. splice reads neither, so refusing boot over one bricked a daemon a person could not reach (a variable
// a systemd unit sets is unset there, not from a terminal), and a saved value is splice's own file, which the daemon
// clears at its start after a copy (SavedSettingsPrune).
package splice.topology

import splice.core.config.RetiredKnob
import splice.core.config.SavedSettings
import splice.core.config.retiredKnobsByKey
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.util.EnvReader

/** The retired knobs [env] still sets, and those saved in the state folder splice finds through [env]. */
public class RetiredSettings(private val env: EnvReader) {
    /** Each alias that is set, then each retired key saved in the state folder [topology] names, or the environment's
     *  when there is no topology to read. A warning names the variable or the key, never its value. */
    public fun warnings(topology: Topology?): List<TopologyFinding> = environment() + saved(topology)

    /** An alias set to nothing is unset, as the knobs read their environment. */
    private fun environment(): List<TopologyFinding> = RetiredKnob.entries.flatMap { knob ->
        knob.envNames.filter { !env(it).isNullOrEmpty() }.map { name ->
            TopologyFinding(
                name,
                "is set in splice's environment and sets ${knob.key}, which ${knob.retired}; splice ignores it, " +
                    "so unset it where it is set, such as a shell profile or the service that starts splice",
                origin = TopologyFinding.Origin.Environment(name),
            )
        }
    }

    private fun saved(topology: Topology?): List<TopologyFinding> {
        val paths = TopologyStatePaths(env).of(topology)
        val file = paths.configFile
        val retired = SavedSettings(file).keys().mapNotNull(retiredKnobsByKey::get).map { knob ->
            TopologyFinding(
                knob.key,
                "is saved in $file and ${knob.retired}; splice ignores it, and the daemon clears it at its next " +
                    "start after a copy to splice's config backups",
                origin = TopologyFinding.Origin.Saved(file, knob.key),
            )
        }
        return retired + ScopedSettings.findings(paths, topology?.heads?.keys.orEmpty())
    }
}
