// The daemon process itself, as the control plane answers for it. One write route today:
//   POST /api/daemon/restart -> DaemonRestartWire   (V4-137, DaemonRoutes.restartJson)
//
// The route has TWO answers and only one of them is a body. A restart the daemon took on is a 202
// carrying the phase it is entering; a restart it refuses is a 409 (nothing supervises this
// daemon, so a drain would be a stop) or a 503 (the wiring never said), each with the reason as
// `{"error": "<sentence>"}`. The refusal is not typed here because it never reaches a caller as a
// body: `request<T>()` throws it as an MgmtError whose message IS the daemon's sentence.

/**
 * The accepted answer, exactly as DaemonRoutes.kt:99-103 writes it: `{"status": "draining"}`.
 *
 * A 202 means the drain was TAKEN ON, never that the daemon came back: the drain outlives the
 * response by as long as the slowest in-flight turn, and what brings the process back is the host's
 * unit, not this route.
 */
export interface DaemonRestartWire {
  status: string;
}
