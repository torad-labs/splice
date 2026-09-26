// NEW: V4-320 — resume a session on another head, as a RECIPE. GET /api/sessions/{id}/resume?head=<key>
// answers the command the operator runs (`<the head's command> -r <id>`), the transcript it resumes,
// the tree that transcript lands in, the model its rows move onto, and whether the original session
// still runs (then the resumed copy diverges from it).
//
// READ ONLY. The daemon cannot start the operator's client, so the act is the operator's: the command
// goes through /launch, where ResumeAcrossHeads.adopt copies and rewrites. This asks the SAME
// resolution (ResumeAcrossHeads.plan) and copies nothing, so what it describes is what the launch does.
//
// REFUSED BY NAME: no head named (400); a head not configured or not launchable (404, naming the
// launchable ones); a head whose command is not linked where `splice install` puts it, since the recipe
// would name a command that does not run (409, with the fix the add flow names); a text that is not a
// session id (400); a session in no head's tree (404, naming the trees searched). Every refusal is the
// control plane's one envelope, `{"error": <sentence>}`, so the console prints it as it prints the rest.
package splice.launch.resume

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.client.resume.ResumeAcrossHeads
import splice.client.resume.ResumePlan
import splice.core.config.InstallPaths
import splice.http.JsonReply
import splice.launch.LaunchHeads
import splice.launch.LaunchSpec
import java.nio.file.Files
import java.nio.file.Path

/** Whether a session is live in Claude Code's registry now; null when no registry is wired. */
public fun interface SessionLive {
    public fun live(sessionId: String): Boolean?
}

/** Whether a head's command is linked where `splice install` puts it: doctor's wrapper check
 *  (DoctorPathCheck), a link in the install bin dir. */
public fun interface WrapperLinked {
    public fun linked(command: String): Boolean
}

public class ResumeRecipeRoute(
    private val heads: LaunchHeads,
    private val live: SessionLive,
    private val linked: WrapperLinked = WrapperLinked { Files.isSymbolicLink(InstallPaths().binDir.resolve(it)) },
    private val resume: ResumeAcrossHeads = ResumeAcrossHeads(),
) {
    public suspend fun recipe(call: ApplicationCall) {
        answer(call.parameters["id"].orEmpty(), call.request.queryParameters["head"]).send(call)
    }

    /** The recipe for resuming [sessionId] on the head keyed [key], or the refusal that says why not. */
    internal fun answer(sessionId: String, key: String?): JsonReply = when (val head = headFor(key)) {
        is HeadFor.Refused -> head.reply
        is HeadFor.Found -> when (val plan = resume.plan(head.spec.trees.own, head.spec.trees.siblings, sessionId)) {
            is ResumePlan.Invalid -> refused(HttpStatusCode.BadRequest, plan.cause)
            is ResumePlan.Absent -> refused(
                HttpStatusCode.NotFound,
                "the session is in no head's transcript tree (searched: ${plan.searchedHeads.joinToString(", ")})",
            )
            is ResumePlan.Owned -> recipe(sessionId, head, plan.transcript, plan.transcript)
            is ResumePlan.Copy -> recipe(sessionId, head, plan.from, plan.into)
        }
    }

    /** The head a recipe is for, or why there is none: settled before any transcript tree is read. */
    private sealed class HeadFor {
        data class Found(val key: String, val command: String, val spec: LaunchSpec) : HeadFor()

        data class Refused(val reply: JsonReply) : HeadFor()
    }

    private fun headFor(key: String?): HeadFor {
        val target = key?.let(heads::byKey)
        val spec = target?.spec
        val command = target?.head?.label
        return when {
            key.isNullOrBlank() ->
                HeadFor.Refused(refused(HttpStatusCode.BadRequest, "name the head to resume on: ?head=<key>"))
            target == null || spec == null || command == null -> {
                val launchable = heads.all().filter { it.spec != null }.joinToString(", ") { it.head.key }
                HeadFor.Refused(
                    refused(HttpStatusCode.NotFound, "no launchable head is keyed '$key' (launchable: $launchable)"),
                )
            }
            !linked.linked(command) -> HeadFor.Refused(
                refused(HttpStatusCode.Conflict, "The $command command is not linked; run splice install $key."),
            )
            else -> HeadFor.Found(target.head.key, command, spec)
        }
    }

    private fun recipe(sessionId: String, head: HeadFor.Found, from: Path, into: Path): JsonReply = JsonReply(
        HttpStatusCode.OK,
        buildJsonObject {
            put("session_id", sessionId)
            put("head", head.key)
            putJsonArray("argv") { listOf(head.command, "-r", sessionId).forEach { add(JsonPrimitive(it)) } }
            put("from", from.toString())
            put("to_tree", into.parent.toString())
            put("copies", from != into)
            put("model", head.spec.pinnedModel)
            put("live", live.live(sessionId))
        }.toString(),
    )

    private fun refused(status: HttpStatusCode, sentence: String): JsonReply =
        JsonReply(status, buildJsonObject { put("error", sentence) }.toString())
}
