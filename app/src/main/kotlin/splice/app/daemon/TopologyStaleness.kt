// PORT-OF: TopologyLoader.staleProbe (app/daemon, before the loader moved to integrations/topology) — the
// control plane's staleness question answered from the loader's digest. It stays with the daemon that
// wires it, because TopologyStale is the configuration feature's port and the loader never sees features.
package splice.app.daemon

import splice.configuration.topology.TopologyStale
import splice.topology.TopologyLoader
import java.nio.file.Path

internal object TopologyStaleness {
    /** JW-04: per-request staleness recompute, failing OPEN — an unreadable file degrades the
     *  signal, never /health. [TopologyLoader.currentDigest] is the fact it wraps. */
    fun probe(path: Path?, bootDigest: String): TopologyStale = TopologyStale {
        val now = path?.let { TopologyLoader.currentDigest(it) }
        now != null && bootDigest.isNotEmpty() && now != bootDigest
    }
}
