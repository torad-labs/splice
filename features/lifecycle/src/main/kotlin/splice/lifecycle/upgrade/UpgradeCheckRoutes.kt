// NEW: the Settings Version card's "Check" (splice-lead, 2026-10-12):
//
//   POST /api/upgrade/check   reads the newest published release once, answers the snapshot (409 while a read runs,
//                             429 with Retry-After when the last began under ten seconds ago)
//
// {state: "never_checked" | "read" | "not_read", latest, read_at_epoch_millis, reason}. The read blocks on the network,
// so it runs on the injected [io] dispatcher and never on a request thread. Unwired answers a named 503, like the rest
// of the upgrade surface.
package splice.lifecycle.upgrade

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val CHECKS_UNWIRED = "Checking for a newer splice is not wired on this daemon."

/** Where the routes read the daemon's [UpgradeChecks] AT CALL TIME (the wiring assigns it after the routes are built). */
public fun interface UpgradeChecksSource {
    public operator fun invoke(): UpgradeChecks?
}

public class UpgradeCheckRoutes(private val checks: UpgradeChecksSource, private val io: CoroutineDispatcher) {
    public suspend fun check(call: ApplicationCall) {
        val source = checks() ?: return unwired(call)
        when (val outcome = withContext(io) { source.check() }) {
            is UpgradeCheckOutcome.Done -> reply(call, view(outcome.snapshot), HttpStatusCode.OK)
            UpgradeCheckOutcome.Busy -> refuse(call, "A check is already running.", HttpStatusCode.Conflict)
            is UpgradeCheckOutcome.TooSoon -> {
                call.response.header(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
                refuse(call, "Checked a moment ago; try again shortly.", HttpStatusCode.TooManyRequests)
            }
        }
    }

    private suspend fun unwired(call: ApplicationCall) =
        refuse(call, CHECKS_UNWIRED, HttpStatusCode.ServiceUnavailable)

    private fun view(snapshot: UpgradeSnapshot): JsonObject = buildJsonObject {
        put("state", snapshot.state.wire)
        put("latest", snapshot.latest?.let(::JsonPrimitive) ?: JsonNull)
        put("read_at_epoch_millis", snapshot.readAtMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", snapshot.reason?.let(::JsonPrimitive) ?: JsonNull)
    }

    private suspend fun refuse(call: ApplicationCall, text: String, status: HttpStatusCode) {
        reply(call, buildJsonObject { put("error", text) }, status)
    }

    private suspend fun reply(call: ApplicationCall, body: JsonObject, status: HttpStatusCode) {
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }
}
