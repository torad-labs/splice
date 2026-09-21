// NEW: V4-127, FEATURES.md §6 — GET /api/upgrade, the console's upgrade bay.
//
// THE PAYLOAD IS THE UPGRADE SURFACE'S OWN, VERBATIM — the same rule the doctor route answers to,
// for the same reason. Installed, latest and whether a rollback is available are read off the
// artifact on disk by code that already knows how to read it; a route that re-derived them would be
// a second opinion about the version of the running daemon, and the console would have to decide
// which of the two to believe. It arrives as JSON TEXT through the UpgradeStatus port.
//
// UNSET ANSWERS A NAMED 503. An empty upgrade object would read as "nothing to upgrade and no
// rollback available", which is a confident false negative about the one question this bay exists to
// answer — and it is indistinguishable, in the payload, from a daemon that genuinely is current.
//
// 404 IS NOT AN OPTION HERE, for the reason V4-136 established: the console reads 404 on a console
// path as route-not-built, so a 404 would be reported to the operator as a missing feature rather
// than a missing wire.
//
// THE ROUTE NEVER FETCHES, AND THE PAYLOAD SAYS SO RATHER THAN PRETENDING. A polled GET that reached
// the network would hold a Ktor worker behind a 300-second timeout and aim the daemon's own request
// pool at a remote host on every poll. The shape to copy is QuotaPoller's, and it is worth naming here
// rather than only where the body lives, because this route is where a reader will look: "A failing
// endpoint is logged once, then silence until it recovers — the bars simply keep the last snapshot."
// A poll that cannot look keeps what it last knew and says WHEN that was; the payload's basis fields
// and its absolute checked_at_epoch_millis are how that honesty reaches the console.
//
// SO THIS ROUTE SERVES AND NEVER RESHAPES. The port composes the value, its basis and its reason; a
// serving layer that trimmed, defaulted or reordered them would be a second opinion about what the
// console is allowed to know, and the two opinions would disagree exactly when it mattered — when a
// basis said `unavailable` and a tidier payload said nothing at all.
package splice.control.api.fleet

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.UpgradeStatus

/** The named reason an unwired upgrade route answers 503 rather than an empty status object. */
private const val UPGRADE_UNWIRED = "the daemon did not wire the upgrade surface; /api/upgrade cannot report it"

internal class UpgradeRoute {

    /** [status] ARRIVES AT CALL TIME, never captured — see DoctorRoute for the whole reasoning; the
     *  two routes took the same constructor seam for the same reason and lose it the same way. */
    suspend fun upgradeJson(call: ApplicationCall, status: UpgradeStatus?) {
        if (status == null) {
            call.respondText(
                buildJsonObject { put("error", UPGRADE_UNWIRED) }.toString(),
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        call.respondText(status(), ContentType.Application.Json)
    }
}
