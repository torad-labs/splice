// PORT-OF: server/src/mgmt/api.mjs ensureMgmtKey @ pre-public-port-baseline — invariant: 32 random bytes hex,
// 0600, minted once per key lifetime and cached in the state dir; the bearer for every /api call.
// Minted EAGERLY before the port opens (a dashboard load must never race an unminted key).
// timingSafe compare.
// SH-12 operator fork, decided: an unreadable-but-PRESENT key file mints-and-warns rather than
// failing daemon start. Reason: this is a single-user loopback daemon whose heads must come up —
// refusing to boot bricks every head over a mgmt-plane blip, while a LOUD rotation costs one
// dashboard re-auth and one line tells the operator exactly what happened and where the key is.
//
// v0.4.0: the mint/compare body moved to [StateKey] so the TURN key ([TurnKey]) can share it. This
// class stays a distinct type on purpose: the management key opens the whole control plane and a
// head's operator routes, and a function that takes a MgmtKey cannot be handed the turn key.
package splice.core.config

import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.WallClock

public class MgmtKey(
    statePaths: StatePaths,
    log: LogSink = LogSink(DaemonLog::write),
    clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val key = StateKey(
        path = statePaths.mgmtKeyFile,
        label = "mgmt-key",
        consequence = "every existing bearer (dashboard session, scripts, the launch shim's stop hook) " +
            "becomes invalid",
        log = log,
        clock = clock,
    )

    /** SH-12: non-null only when THIS process minted (fresh key). Doctor/status compare it to
     *  daemon uptime — a key minted minutes ago on an hours-old install is the rotated-bearer
     *  signature that used to surface only as unexplained 401s. */
    public val mintedAtMs: Long? get() = key.mintedAtMs

    public fun get(): String = key.get()

    /** Constant-time check of a raw presented credential (no scheme parsing). */
    public fun matches(presented: String?): Boolean = key.matches(presented)

    /** Constant-time bearer check (`Authorization: Bearer <key>`) — scheme parsing shared with
     *  HeadServer.authorize via [splice.core.auth.BearerScheme.bearerToken] so the same token bytes
     *  work on both planes. */
    public fun matchesBearer(header: String?): Boolean = key.matchesBearer(header)
}
