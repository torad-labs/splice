// NEW: V4-137, split out of V4-127 — POST /api/daemon/restart, the V4-74 draining restart.
//
// THE RESTART IS THE EXISTING DRAIN, AND THIS ROUTE ADDS NO MECHANISM. It takes the `shutdownDaemon`
// seam that POST /api/daemon/shutdown already uses (Main.kt:148 completes a signal the main coroutine
// waits on), so the daemon stops taking turns, lets the in-flight ones finish in its own order, and
// exits — drain 45s under a 50s head budget under a 55s cap. What brings it BACK is not this repo's:
// the host's systemd unit carries Restart=always with RestartSec=2 and no start limit, so a
// drain-and-exit returns in about two seconds.
//
// THAT IS WHY NOTHING HERE RELAUNCHES ANYTHING. A splice-side relauncher would be a SECOND supervisor
// racing `Restart=always` on the same process — the hostshield scar exactly (two reapers sharing one
// escalation state file collapsed a 30s TERM-to-KILL grace to ~2s over two days). The unit is
// hostshield's own; a change to it is a hostshield ledger entry, never an edit from here.
//
// AND THAT IS NOT TRUE OF EVERY DAEMON, WHICH IS THE ROW'S REAL CONTENT. Someone who ran
// `splice.jar daemon` by hand is supervised by nothing: taking the drain there would STOP the daemon
// for good, so a console restart button would be a console stop button — strictly worse than having no
// button at all. The daemon can know, because systemd sets INVOCATION_ID in the environment of every
// unit it starts. So the two arms are:
//
//   supervised   -> take the drain, answer 202 meaning the request was TAKEN ON, never that the
//                   daemon came back. The drain outlives this response by as long as the slowest
//                   in-flight turn, so a 200 would claim a completion nobody can promise yet.
//   unsupervised -> REFUSE, named, and take no drain at all. Same family as V4-127's upgrade port:
//                   a legitimate-looking answer from a mechanism that did not run.
//
// NOTHING IS DRAINED BEFORE THE RESPONSE IS WRITTEN. The 202 goes out first and the drain is requested
// after, which is the order POST /api/daemon/shutdown already uses and for the same reason: the daemon
// is about to tear itself down, and a request that acted first would race its own reply out of the
// socket it is closing.
//
// IT IS NOT SERVED BY RestartCommand.restart, and the reason is worth keeping: that path calls
// stopIfRunning -> DaemonStop.stopDaemon, the cooperative-then-SIGTERM-then-SIGKILL ladder, which run
// from inside the daemon's own handler kills the process writing the response — the 202 never lands,
// in-flight turns are CUT rather than drained, and the cold start it then performs runs from the
// service's environment. That is the finding this row was cut from.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.ShutdownDaemon

/** The status word an accepted restart answers with: the phase the daemon is entering, not one it has
 *  reached. */
private const val DRAINING = "draining"

/** Refused because the wiring never told this daemon how to find out whether anything restarts it.
 *
 *  A SEPARATE REFUSAL FROM [RESTART_UNSUPERVISED] even though both refuse, because they are different
 *  facts with different fixes: this one is a wiring gap an operator closes in the daemon, the other is
 *  a true statement about how this process was started. Collapsing them would send someone to look for
 *  a missing wire on a hand-started daemon, or to restart a unit that is already correct. */
internal const val RESTART_SUPERVISION_UNWIRED =
    "the daemon did not wire a supervision probe; /api/daemon/restart cannot tell whether anything " +
        "would bring it back, and refusing is the only honest answer"

/** Refused because nothing will restart this process. NOT a 202, and not a drain: the daemon is up,
 *  serving, and was started in a way that gives it no relaunch half. */
internal const val RESTART_UNSUPERVISED =
    "nothing will restart this daemon: it was not started by systemd, so a drain would leave it down"

/**
 * Whether anything would bring this process back after it exits.
 *
 * A ROLE AND NOT A BOOLEAN CONSTANT because the answer depends on how the process was STARTED, which
 * only the daemon's own wiring knows: production reads it from the environment, and every test rig
 * answers it directly rather than inheriting whatever started the JVM running the tests.
 */
public fun interface DaemonSupervised {
    public operator fun invoke(): Boolean
}

internal class DaemonRoutes {

    /** [shutdown] and [supervised] ARRIVE AT CALL TIME: the routing lambda reads the server's own
     *  properties as it calls, so neither is captured, and a route that captured them would answer
     *  against wiring that arrived a moment later. */
    suspend fun restartJson(
        call: ApplicationCall,
        shutdown: ShutdownDaemon,
        supervised: DaemonSupervised?,
    ) {
        if (supervised == null) {
            refuse(call, RESTART_SUPERVISION_UNWIRED, HttpStatusCode.ServiceUnavailable)
            return
        }
        // THE DID-NOT-RUN, REFUSED. Taking the drain here would turn this route into a stop button on
        // any daemon the operator started by hand, and the console would render a restart that never
        // comes back.
        if (!supervised()) {
            refuse(call, RESTART_UNSUPERVISED, HttpStatusCode.Conflict)
            return
        }
        call.respondText(
            buildJsonObject { put("status", DRAINING) }.toString(),
            ContentType.Application.Json,
            HttpStatusCode.Accepted,
        )
        shutdown()
    }

    private suspend fun refuse(call: ApplicationCall, message: String, status: HttpStatusCode) {
        call.respondText(
            buildJsonObject { put("error", message) }.toString(),
            ContentType.Application.Json,
            status,
        )
    }
}
