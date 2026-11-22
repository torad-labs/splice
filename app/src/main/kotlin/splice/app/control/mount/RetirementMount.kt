// NEW: head-catalog (gw-retire, gw-check). The routes `splice claude retire`, `restore` and `check` ask, behind the
// management key:
//
//   POST /api/claude/retire    {"heads": [<command|key>...]} or {"all": true}: retire them (HeadRetirement)
//   POST /api/claude/restore   the same shape: bring them back from their backups
//   GET  /api/claude/heads     how each head's command launches now, read without launching anything
//
// Retire and restore always answer 200 with {"ok", "said": [...]}: a head refused is part of the answer, said beside
// the ones that did retire, and `ok` false sets the command's exit status.
package splice.app.control.mount

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.app.head.HeadRetirement
import splice.core.util.JsonScalars
import splice.http.JsonBody
import splice.http.JsonReply

internal class RetirementMount(private val retirement: HeadRetirement, private val guard: ControlGuard) {
    private val jsonBody = JsonBody()

    fun register(route: Route) {
        route.post("/api/claude/retire") {
            guard.guarded(call) { asked(call) { names, all -> retirement.retire(names, all) } }
        }
        route.post("/api/claude/restore") {
            guard.guarded(call) { asked(call) { names, all -> retirement.restore(names, all) } }
        }
        route.get("/api/claude/heads") {
            guard.guarded(call) { JsonReply(HttpStatusCode.OK, retirement.launchFacts().toString()).send(call) }
        }
    }

    /** [act] on the heads the body names, or on all of them, answered whole. */
    private suspend fun asked(call: ApplicationCall, act: RetirementAct) {
        val body = jsonBody.parse(call) ?: JsonObject(emptyMap())
        val names = (body["heads"] as? JsonArray).orEmpty().mapNotNull(JsonScalars::str)
        val all = JsonScalars.str(body, "all") == "true"
        JsonReply(HttpStatusCode.OK, act(names, all).toString()).send(call)
    }
}

/** Retiring or restoring the heads [names] name, or every one with [all]. */
internal fun interface RetirementAct {
    suspend operator fun invoke(names: List<String>, all: Boolean): JsonObject
}
