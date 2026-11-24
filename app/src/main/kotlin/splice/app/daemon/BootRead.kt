// NEW: what the daemon reads at boot before its lock, split out of Main: splice.toml with every finding beside the
// file's own (what a local runtime that is up refuses, a retired knob splice's environment or saved settings still
// set) and the modes this jar carries (modes/README.md), whose catalog is read once here and kept for the daemon's life.
package splice.app.daemon

import splice.app.modes.ModeBoot
import splice.app.provider.local.LocalRowBootFindings
import splice.core.util.EnvReader
import splice.topology.BootSettings
import splice.topology.LocalRowFindings

internal class BootRead(private val env: EnvReader) {
    /** The one catalog of the modes this jar carries. */
    val modes: ModeBoot = ModeBoot()

    /** What each local runtime that is up refuses of splice.toml's rows. */
    fun runtimes(): LocalRowFindings = LocalRowBootFindings(env)

    /** What boot reads beside the file: the retired knobs still set, and the modes' findings and names. */
    fun beside(): BootSettings = modes.beside(env)
}
