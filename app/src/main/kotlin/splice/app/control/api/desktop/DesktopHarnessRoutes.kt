// NEW: desktop-harnesses. The control plane's routes for which program each Claude Desktop session runs on, behind
// the management key:
//
//   GET   /api/desktop/harnesses   the harness map as Settings and Accounts draw it (DesktopHarnessRouteOps.snapshot):
//                                  each family with a choice, its harness now and who chose it, every prefix's switch,
//                                  and whether the daemon must start again for a change to apply
//   PATCH /api/desktop/harnesses   {"<family>": {"harness"?: id, "enabled"?: bool} | null, ...}: each edit kept as the
//                                  console's choice, each null put back to the default, and the whole map written to
//                                  `<state>/desktop-harnesses.json` at once. An unknown family, harness or field, or a
//                                  value not of its kind, refuses the whole body with 400; a file splice can't read
//                                  refuses with 409 and is left as it is
//
// One change at a time: the file is read, changed and written under one lock, off the request thread, and answered
// after the lock is let go. No answer is kept by a cache.
package splice.app.control.api.desktop

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.desktop.harness.HarnessFileRead
import splice.app.desktop.harness.HarnessTarget
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException

/** The daemon's harness map, asked at each request; null while the daemon has not wired it. */
internal fun interface HarnessTargetSource {
    operator fun invoke(): HarnessTarget?
}

internal class DesktopHarnessRoutes(private val target: HarnessTargetSource) {
    private val ops = DesktopHarnessRouteOps()
    private val mutation = Mutex()
    private val io = ProcessDispatchers().io()

    suspend fun read(call: ApplicationCall) {
        val desktop = target() ?: return reply(call, unwired())
        val body = withContext(io) { snapshot(desktop, desktop.harnesses.file.read()) }
        reply(call, Answer(HttpStatusCode.OK, body))
    }

    suspend fun patch(call: ApplicationCall) {
        val desktop = target() ?: return reply(call, unwired())
        val body = JsonScalars.objectOrNull(Json, call.receiveText())
            ?: return reply(call, Answer(HttpStatusCode.BadRequest, refused("expected a JSON object of families")))
        val answer = when (val patch = ops.parse(body)) {
            is HarnessPatch.Refused -> {
                val families = JsonObject(patch.families.mapValues { JsonPrimitive(it.value) })
                Answer(HttpStatusCode.BadRequest, JsonObject(refused(patch.why) + ("families" to families)))
            }
            is HarnessPatch.Changes -> mutation.withLock { withContext(io) { changed(desktop, patch) } }
        }
        reply(call, answer)
    }

    /** [patch] kept and the map written, answered with the map after it; or refused with nothing changed. */
    private fun changed(desktop: HarnessTarget, patch: HarnessPatch.Changes): Answer {
        val harnesses = desktop.harnesses
        val read = harnesses.file.read()
        if (read is HarnessFileRead.Unreadable) {
            return Answer(HttpStatusCode.Conflict, refused(read.why + HARNESS_FILE_FIX))
        }
        val held = harnesses.choicesOf(read)
        val choices = ops.changed(held, harnesses.states(held), patch)
        try {
            harnesses.save(choices)
        } catch (failure: IOException) {
            val why = "the Desktop harnesses were not saved: ${SafeFailureText.render(failure)}"
            return Answer(HttpStatusCode.InternalServerError, refused(why))
        }
        return Answer(HttpStatusCode.OK, snapshot(desktop, harnesses.file.read()))
    }

    private fun snapshot(desktop: HarnessTarget, read: HarnessFileRead): JsonObject {
        val harnesses = desktop.harnesses
        val restart = harnesses.restartPending(harnesses.states(harnesses.choicesOf(read)))
        return ops.snapshot(desktop, read, restart)
    }

    /** A status and the body it is answered with. */
    private data class Answer(val status: HttpStatusCode, val body: JsonObject)

    private fun refused(why: String): JsonObject = buildJsonObject { put("error", why) }

    private fun unwired(): Answer = Answer(HttpStatusCode.ServiceUnavailable, refused(HARNESSES_NOT_WIRED))

    private suspend fun reply(call: ApplicationCall, answer: Answer) {
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(answer.body.toString(), ContentType.Application.Json, answer.status)
    }
}

// What the routes answer before the daemon has wired its Desktop part.
private const val HARNESSES_NOT_WIRED = "splice desktop: the Desktop harnesses open once the daemon's door is up"

// What a person does about a harness file splice can't read, after why.
private const val HARNESS_FILE_FIX = "; fix it or move it aside, and Settings shows splice's defaults again"
