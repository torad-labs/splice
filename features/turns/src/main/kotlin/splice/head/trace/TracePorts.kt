// NEW: what `splice trace` needs from outside the turns feature (LAYOUT-01): the configured heads,
// read from the topology app loads. The read has two honest answers, so it is a sealed result.
package splice.head.trace

import splice.core.util.EnvReader
import java.io.IOException
import java.nio.file.Path

/** Reads the configured head names, so a misspelt head is refused with the heads that exist. */
public fun interface TraceHeadSource {
    public fun load(env: EnvReader): TraceHeads
}

/** Where the daemon writes traces. The daemon's state dir honours `[daemon].state_dir`, which only
 *  the topology knows, so app resolves it; the environment alone would read the wrong dir (V4-109). */
public fun interface TraceDirSource {
    public fun traceDir(env: EnvReader): Path
}

/** The topology file [path] the heads were read from, and what the read found. */
public sealed class TraceHeads {
    public abstract val path: String

    public data class Configured(override val path: String, public val names: Set<String>) : TraceHeads()

    public data class Unreadable(override val path: String, public val failure: IOException) : TraceHeads()
}
