package splice.topology

import splice.core.config.SavedSettings
import splice.core.config.StatePaths
import splice.core.config.daemonWideKnobs
import splice.core.config.knobsByKey
import splice.core.topology.TopologyFinding

/** A saved head override cannot silently replace a daemon-wide policy after an upgrade. */
internal object ScopedSettings {
    fun findings(paths: StatePaths, heads: Set<String>): List<TopologyFinding> = heads.flatMap { head ->
        val file = paths.headsDir.resolve(head).resolve(paths.configFile.fileName)
        SavedSettings(file).keys().filter { knobsByKey[it] in daemonWideKnobs }.map { key ->
            TopologyFinding(
                "heads.$head.overrides.$key",
                "is daemon-wide; clear the saved head value and set it under [defaults] instead",
                origin = TopologyFinding.Origin.Saved(file, key),
            )
        }
    }
}
