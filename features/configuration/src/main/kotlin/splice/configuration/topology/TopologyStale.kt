// PORT-OF: daemon/control/.../ControlPorts.kt (TopologyStale) — the one topology question the control
// plane injects, moved beside the routes that serve it (LAYOUT-01). Named by role, never by shape:
// a liveness probe is `() -> Boolean` too.
package splice.configuration.topology

/**
 * Whether the topology on disk has diverged from the one this daemon runs — recomputed per request,
 * FAIL-OPEN (false when it cannot tell).
 *
 * Reporting only. Topology is deliberately not hot-reloadable, so this exists to make the required
 * restart VISIBLE to the shim, doctor and dashboard, never to trigger one. The context windows are
 * the exception (V4-162): the daemon re-reads those, so an edit to nothing else is never stale.
 */
public fun interface TopologyStale {
    public operator fun invoke(): Boolean
}
