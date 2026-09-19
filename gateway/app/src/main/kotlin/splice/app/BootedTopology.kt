// NEW: the booted topology's identity and what it declared, as ONE value — the three fields
// ControlPlane carried separately and always used together.
//
// WHY IT EXISTS, and it is a wall's answer rather than a preference. ControlPlane crossed the
// constructor-width ratchet on 2026-09-18 at 13 parameters against a max of 12, and the two that
// took it from 11 were e6e8da60 (declaredHeads, V4-127) and 40369f4d (compactionInstructions,
// V4-136) — each correct on its own and neither aware of the other. detekt cannot see this growth
// at all (gateway/detekt.yml:38-43 ignores defaulted parameters, which all three of these are),
// which is exactly why checks/constructor-width.ts exists.
//
// THESE THREE AND NOT SOME OTHER THREE. The remedy the wall names is "take the bundle apart", and
// the honest bundle is the one whose members describe a single thing rather than the one that
// happens to shorten the list. `topologyDigest` and `topologyPath` are the booted config's identity
// (JW-04), and `declaredHeads` is what that same booted config declared — the three answer one
// question, "which topology is this daemon running and what did it say", and ControlPlane reads
// them within four lines of each other (the doctor payload at :125-127 and the port assignment at
// :147). The remaining ten parameters are unrelated collaborators and grouping any of them would be
// a parameter object in name only.
//
// IT DELIBERATELY DOES NOT HOLD THE Topology OBJECT. ControlPlane has never had it and must not:
// only Daemon does, and V4-127's own comment records why — a second read of the file the heads were
// built from can diverge from the heads themselves. This carries the digest, the path, and a
// lambda over what was declared, which is the same contract the three fields had.
package splice.app

import splice.control.DeclaredHeads
import java.nio.file.Path

/** The booted config's identity plus what it declared. Defaults are the same "not supplied" values
 *  the three parameters carried individually, so a caller that named none of them is unchanged. */
internal data class BootedTopology(
    /** JW-04: sha-256 of the parsed bytes. Empty when no topology was loaded. */
    val digest: String = "",
    /** The resolved path the digest was taken from, or null when there is no file. */
    val path: Path? = null,
    /** V4-127: the provider key and declared model list for every head, for the whole daemon at
     *  once. Built by Daemon, which is the only holder of the Topology. */
    val declaredHeads: DeclaredHeads = DeclaredHeads { emptyMap() },
    /** V4-162: the version the daemon RUNS, which a window-only edit moves (TopologyWindows). Null =
     *  nothing re-reads the file, so the booted [digest] is the running one and any change is stale. */
    val running: RunningTopology? = null,
)

/** V4-162: which splice.toml version the daemon runs, and whether the file has moved past it in a way
 *  only a restart applies — what /health publishes as topologyDigest and topologyStale. */
internal interface RunningTopology {
    fun digest(): String

    fun stale(): Boolean
}
