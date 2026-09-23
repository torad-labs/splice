// The daemon entity's HTTP segment: the draining restart, no rendering. The client helper carries the
// key, the 401 lockout and the error envelope, so nothing here re-implements any of them.
import { request } from '@shared/api';
import type { DaemonRestartWire } from '../model/types';

/**
 * Ask the daemon to drain and exit so its supervisor brings it back (FEATURES.md 4.2, "Daemon restart
 * drains turns first").
 *
 * A REFUSAL THROWS, and that is the point, the same as the claude-head wrap: the daemon answers 409
 * "nothing will restart this daemon..." on a hand-started process and 503 when its wiring gave it no
 * way to know, and the sentence is the whole content of that answer. No `pendingOf`: the route is
 * served, so a 404 is a daemon that stopped answering, not a row that has not landed.
 */
export async function restartDaemon(): Promise<DaemonRestartWire> {
  return request<DaemonRestartWire>('/api/daemon/restart', { method: 'POST' });
}
