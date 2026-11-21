// NEW: the modes this jar carries (modes/README.md), read once through the catalog: at boot, the daemon keeps this one
// reading for its life and the list, the door, the routes and the switch all read it; `splice check-config` reads the
// same jar the same way. What the catalog finds wrong is a boot finding beside splice.toml's own.
package splice.app.modes

import splice.core.mode.FoundModes
import splice.core.topology.TopologyFinding
import splice.modes.api.ModeCatalog
import splice.modes.api.ModeCatalogReader

/** The catalog of the jar [loader] reads, and what boot is handed of it. */
internal class ModeBoot(loader: ClassLoader = ClassLoader.getSystemClassLoader()) {
    val catalog: ModeCatalog = ModeCatalogReader(loader).read()

    /** The names of the modes found, every one the index lists (a broken one too, so a table naming it is not also
     *  told it names no mode), and each catalog finding, named by its mode. */
    fun found(): FoundModes {
        val findings = catalog.findings.map { said ->
            val mode = said.substringBefore(':').removePrefix("mode ").trim()
            TopologyFinding("mode $mode", said.substringAfter(':').trim(), origin = TopologyFinding.Origin.Mode(mode))
        }
        return FoundModes(catalog.listed.toSet(), findings)
    }
}
