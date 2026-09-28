// NEW: V4-137, split out of V4-127 — POST /api/daemon/restart, the V4-74 draining restart.
//
// Both restart arms use the existing drain, after the response. A systemd-owned daemon relies on
// Restart=always; it must never start a second supervisor. A default install has no unit, so it
// arms a detached successor BEFORE taking the drain. That successor waits until this process exits
// and then uses the CLI's raw cold-start path. A failed arm refuses without draining. The 202 means
// the request was taken on, never that the new process has already answered /health.
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
//
// V4-220: A COMPACTION IN FLIGHT IS WAITED FOR FIRST (RestartAfterCompactions), as `splice restart`
// waits since V4-216. The 202 then says `waiting` and names the compactions; the daemon keeps serving,
// drains once they are gone, and GET /api/daemon/restart reports the phase. `?now=1` skips the wait.
package splice.lifecycle.restart

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The status word an accepted restart answers with: the phase the daemon is entering, not one it has
 *  reached. */
private const val DRAINING = "draining"
private const val WAITING = "waiting"
private const val IDLE = "idle"

/** The query values that skip the compaction wait, as `splice restart --now` does. */
private val NOW_VALUES = setOf("1", "true")

/** Refused because the wiring never told this daemon how to find out whether anything restarts it.
 *
 *  A SEPARATE REFUSAL FROM [RESTART_UNSUPERVISED] even though both refuse, because they are different
 *  facts with different fixes: this one is a wiring gap an operator closes in the daemon, the other is
 *  a true statement about how this process was started. Collapsing them would send someone to look for
 *  a missing wire on a hand-started daemon, or to restart a unit that is already correct. */
internal const val RESTART_SUPERVISION_UNWIRED =
    "the daemon did not wire a supervision probe; /api/daemon/restart cannot tell whether anything " +
        "would bring it back, and refusing is the only honest answer"

/** Refused only when no detached successor was wired for this unsupervised daemon. */
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

/** [restarts] is the daemon's one decision and wait-then-drain: every restart the daemon itself takes
 *  on goes through it, a console add's save included. */
public class DaemonRoutes(private val restarts: DaemonRestarts) {

    /** [shutdown] and [supervised] ARRIVE AT CALL TIME: the routing lambda reads the server's own
     *  properties as it calls, so neither is captured, and a route that captured them would answer
     *  against wiring that arrived a moment later. */
    public suspend fun restartJson(
        call: ApplicationCall,
        shutdown: ShutdownDaemon,
        supervised: DaemonSupervised?,
    ) {
        val now = call.request.queryParameters["now"] in NOW_VALUES
        // DaemonRestarts arms the detached successor before accepting an unsupervised drain.
        when (val taken = restarts.take(now, shutdown, supervised)) {
            is RestartTaken.Refused -> {
                val status = if (taken.unwired) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Conflict
                refuse(call, taken.reason, status)
            }
            is RestartTaken.Accepted -> {
                val body = phaseJson(taken.phase).toString()
                call.respondText(body, ContentType.Application.Json, HttpStatusCode.Accepted)
                if (taken.phase is RestartPhase.Draining) shutdown()
            }
        }
    }

    /** GET /api/daemon/restart: where a restart the daemon took on stands. */
    public suspend fun statusJson(call: ApplicationCall) {
        call.respondText(phaseJson(restarts.phase()).toString(), ContentType.Application.Json)
    }

    private fun phaseJson(phase: RestartPhase): JsonObject = buildJsonObject {
        when (phase) {
            RestartPhase.Idle -> put("status", IDLE)
            RestartPhase.Draining -> put("status", DRAINING)
            is RestartPhase.Waiting -> {
                put("status", WAITING)
                putJsonArray("compactions") {
                    phase.compactions.forEach { slot ->
                        addJsonObject {
                            put("head", slot.head)
                            put("age_ms", slot.ageMs)
                        }
                    }
                }
            }
        }
    }

    private suspend fun refuse(call: ApplicationCall, message: String, status: HttpStatusCode) {
        call.respondText(
            buildJsonObject { put("error", message) }.toString(),
            ContentType.Application.Json,
            status,
        )
    }
}
