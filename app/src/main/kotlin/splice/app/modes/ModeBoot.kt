// NEW: the modes this jar carries (modes/README.md), read once through the catalog: at boot, the daemon keeps this one
// reading for its life and the list, the door, the routes and the switch all read it; `splice check-config` reads the
// same jar the same way. What the catalog finds wrong is a boot finding beside splice.toml's own.
package splice.app.modes

import splice.app.control.engine.ControlRouteClaims
import splice.core.mode.FoundModes
import splice.core.topology.TopologyFinding
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.modes.api.ModeCatalog
import splice.modes.api.ModeCatalogReader
import splice.topology.BootSettings
import splice.topology.RetiredSettings
import splice.topology.TopologyStatePaths

/** The catalog of the jar [loader] reads, and what boot is handed of it. */
internal class ModeBoot(private val loader: ClassLoader = ClassLoader.getSystemClassLoader()) {
    private val builtIn = ModeCatalogReader(loader).read()
    private var external: LoadedMods? = null
    val catalog: ModeCatalog get() = external?.catalog ?: builtIn

    fun beside(env: EnvReader): BootSettings {
        val paths = TopologyStatePaths(env)
        return BootSettings(RetiredSettings(env)) { mods, configDir, topology ->
            val statePaths = paths.of(topology)
            external = if (mods.dir != null || mods.jars.isNotEmpty()) {
                ExternalModes(loader, builtIn, mods, configDir, statePaths.stateDir).read()
            } else {
                null
            }
            found(ControlRouteClaims(env).of(catalog, statePaths))
        }
    }

    /** Called only for daemon boot after a clean read and after its state directory is secured. */
    fun persist() {
        external?.persist()
    }

    fun log(log: LogSink) {
        external?.log(log)
    }

    /** The names of the modes found, every one the index lists (a broken one too, so a table naming it is not also
     *  told it names no mode), and each catalog finding, named by its mode. */
    private fun found(claims: List<TopologyFinding>): FoundModes {
        val findings = builtIn.findings.map { said ->
            val mode = said.substringBefore(':').removePrefix("mode ").trim()
            TopologyFinding("mode $mode", said.substringAfter(':').trim(), origin = TopologyFinding.Origin.Mode(mode))
        }
        return FoundModes(catalog.listed.toSet(), findings + external?.findings.orEmpty() + claims)
    }
}
