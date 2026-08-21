// NEW: the POSIX process-lifecycle half of stopping a daemon — the escalation ladder (cooperative
// shutdown, SIGTERM, SIGKILL), the port-release poll, and the `ss`-scoped process lookup that keeps
// every signal aimed at the process holding the TARGET control port. Split from ControlPlaneClient,
// which is HTTP transport and nothing else: this file shells out to `ss`, matches process cmdlines,
// signals through ProcessHandle and polls TCP ports — none of which is HTTP. Symmetric with
// DaemonLaunch, which exists on the cold-start side for exactly the same reason.
// :app is wall-exempt for println (a terminal tool writes to stdout).
package splice.app.cli

import splice.app.DaemonBoundary
import splice.app.DaemonProbe

/** Stopping the daemon: ask over the control plane, then escalate through OS signals until every
 *  port it owned is free. Constructed by the `restart` verb (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions); every member keeps the old function's name. */
internal class DaemonStop {

    private val boundary = DaemonBoundary()

    /** Ask the daemon to shut down (bearer-guarded) and wait until the LISTENER is actually gone.
     *  The POST is fire-and-observe: a graceful teardown can drop the connection mid-response
     *  (read-timeout) before it 2xx's, so the POST outcome is NOT the signal — the stop poll is.
     *  Failure is reported only when the port is still bound after the whole poll budget. */
    fun stopDaemon(port: Int, key: String, headPorts: List<Int> = emptyList()): Boolean {
        // F1: SEE the shutdown status — a 401/403 names the root cause (mgmt-key mismatch), which
        // the old fire-and-forget silently swallowed, then escalated as if the daemon were merely
        // slow (observed twice on 2026-08-11). statusOf does not gate on 2xx the way request() does.
        when (val status = ControlPlaneClient.statusOf("http://127.0.0.1:$port/api/daemon/shutdown", "POST", key)) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> println(
                "splice: shutdown request REJECTED — the mgmt key on disk does not match the " +
                    "running daemon's. Escalating to OS signals (scoped to the daemon on :$port).",
            )
            null -> Unit // transport drop on graceful teardown is expected; the poll decides
            in HTTP_OK..HTTP_LAST_SUCCESS -> Unit // 202 Accepted: the daemon is stopping cooperatively
            // Everything else — 404 from a daemon predating the endpoint, 500, 503 — used to fall
            // into the same `else` as 202 and be read as a cooperative stop, so the CLI sat out the
            // whole graceful rung waiting on a request the daemon never honoured.
            else -> println("splice: shutdown returned HTTP $status — not an accepted stop; escalating.")
        }

        // Escalation ladder. Each rung advances only while a port is still bound (release is the
        // sole success signal — BS-4), and every kill is SCOPED to the process actually holding the
        // TARGET control port (F2): a bare cmdline match would SIGKILL every splice daemon on the
        // box, production and a mid-run oracle daemon included. SIGTERM engages the daemon's own
        // 8s cooperative stop + 10s halt(0) floor, so the SIGTERM rung waits past that floor.
        if (pollStopped(port, headPorts, GRACEFUL_POLLS)) return true
        escalate(port, "SIGTERM", "ignored the shutdown request") { it.destroy() }
        if (pollStopped(port, headPorts, SIGTERM_POLLS)) return true
        escalate(port, "SIGKILL", "survived SIGTERM past the halt floor") { it.destroyForcibly() }
        return pollStopped(port, headPorts, SIGKILL_POLLS)
    }

    /** Send one rung's signal, and SAY SO when it could not be sent.
     *
     *  Both rungs used `daemonOnPort(port)?.let { … }`, so every not-found case — `ss` missing from
     *  PATH (pidsOnPort's IOException becomes an empty list), an unreadable commandLine, or a daemon
     *  launched in a shape the cmdline predicate does not match (`./gradlew run`, a wrapper, a
     *  versioned jar) — skipped the signal in total silence. The "escalation ladder" then degraded
     *  to plain polling and the operator was told only "the daemon did not stop", with no hint that
     *  nothing was ever signalled. destroy()/destroyForcibly() also RETURN whether the signal was
     *  delivered, and both returns were discarded while the preceding println already claimed it
     *  had been sent. */
    private fun escalate(port: Int, signal: String, why: String, send: SignalSend) {
        val handle = boundary.daemonOnPort(port)
        if (handle == null) {
            println(
                "splice: could not identify the process holding :$port — cannot send $signal " +
                    "(is `ss` on PATH? was the daemon started from a non-standard jar?)",
            )
            return
        }
        println("splice: daemon pid ${handle.pid()} on :$port $why — $signal")
        if (!send(handle)) {
            println("splice: $signal to pid ${handle.pid()} was REFUSED (not permitted / already gone)")
        }
    }

    private fun pollStopped(port: Int, headPorts: List<Int>, polls: Int): Boolean {
        repeat(polls) {
            if (stopped(port, headPorts)) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return stopped(port, headPorts)
    }

    /** "Stopped" means EVERY port this daemon owned is free — the control port AND the head ports.
     *  Checking only the control port (F3) let the ladder report success while :3099 lingered on
     *  non-daemon Netty threads; the next restart then boots a head into EADDRINUSE and it lands
     *  permanently failed. A daemon whose control server quit answering can still hold its ports. */
    private fun stopped(port: Int, headPorts: List<Int>): Boolean =
        DaemonProbe.healthVersion(port) == null &&
            !AdminSupport.controlPortBound(port) &&
            headPorts.none { AdminSupport.controlPortBound(it) }
}

// 11s: the daemon's cooperative cap is STOP_DEADLINE_MS (8s) and its halt(0) floor sits at
// STOP_DEADLINE_MS + TEARDOWN_TAIL_GRACE_MS (10s). At 32 polls this rung expired at EXACTLY 8s,
// so a daemon using its full budget was SIGTERM'd mid drain()/lock.close() tail — re-entering
// shutdown() and arming a second watchdog that can halt the very drain it was waiting on.
// Waiting past the floor means the cooperative path wins whenever it is going to win at all.
private const val GRACEFUL_POLLS = 44
private const val SIGTERM_POLLS = 48 // 12s: past the 10s halt(0) floor the SIGTERM hook guarantees
private const val SIGKILL_POLLS = 12 // 3s: kernel teardown + port release
private const val HTTP_OK = 200
private const val HTTP_LAST_SUCCESS = 299
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val POLL_INTERVAL_MS = 250L
