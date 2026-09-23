// NEW: LAYOUT-01 — where a console route reads the daemon's ONE splice.toml writer. Two capabilities
// write through it (PUT /api/topology and the per-head capture switch), so the role lives beside
// [TopologyWriter] in the shared kernel rather than as one copy per feature (role registry, V4-89).
package splice.core.topology

/** The daemon's topology writer, read per request: ControlPlane assigns it after the routes are
 *  constructed, and a route that captured the value would answer unwired forever. */
public fun interface TopologyWriterSource {
    public operator fun invoke(): TopologyWriter?
}
