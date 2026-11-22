// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). The one open both who run agy share:
// `initialize`, then the session [resume] taken up or a new one ([hello], then [opened]). On it a Desktop session runs
// ([AgyOpen.Ready] carries what the session needs); through it [AgyModels] asks what the account is offered. A session
// agy does not know (one that began on Claude, decision 5) opens a new one instead of failing, and Desktop keeps its
// own id for it. A signed-out agy, or one that ends or refuses, leaves the session unusable with what to tell the
// person.
package splice.provider.antigravity

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** What opening agy came to. */
internal sealed class AgyOpen {
    data class Ready(val session: AgySession) : AgyOpen()

    /** agy can run no turn, and what to tell the person. */
    data class Unusable(val reason: String) : AgyOpen()
}

/** Opens an agy session on [agy] in folder [cwd]; each ask waits at most [ms] when said so, and as long as it takes
 *  otherwise. */
internal class AgyOpener(
    private val agy: AcpConnection,
    private val cwd: String,
    private val ms: Long? = null,
) {
    private val requests = AcpRequests()

    /** Session [resume] taken up again, or a new one when there is none to take up or agy does not know it. */
    suspend fun open(resume: String?): AgyOpen {
        val hello = hello()
        if (hello !is AcpAnswer.Result) return unusable(hello)
        val prompts = (hello.result["agentCapabilities"] as? JsonObject)?.get("promptCapabilities") as? JsonObject
        val images = JsonScalars.str(prompts, "image") == "true"
        val (id, answered) = opened(resume)
        val result = (answered as? AcpAnswer.Result)?.result ?: return unusable(answered)
        return id?.let { ready(it, result, images) } ?: AgyOpen.Unusable("agy opened no session")
    }

    /** agy's answer to `initialize`. */
    suspend fun hello(): AcpAnswer = asked("initialize", requests.initialize())

    /** The session [resume] when agy takes it up (or refuses it for want of a sign-in), else a new one: its id and
     *  everything agy answered with; whatever went wrong instead is in that answer too. */
    suspend fun opened(resume: String?): Pair<String?, AcpAnswer> {
        val resumed = resume?.let { asked("session/resume", requests.resume(it, cwd)) }
        val signedOut = resumed is AcpAnswer.Refused && resumed.code == AUTH_REQUIRED
        val taken = resumed is AcpAnswer.Result || signedOut
        if (resumed != null && taken) return resume to resumed
        val beganNew = asked("session/new", requests.newSession(cwd))
        return (beganNew as? AcpAnswer.Result)?.let { JsonScalars.str(it.result, "sessionId") } to beganNew
    }

    private fun ready(id: String, answer: JsonObject, images: Boolean): AgyOpen {
        val options = answer["configOptions"] as? JsonArray ?: JsonArray(emptyList())
        val mode = JsonScalars.str(answer["modes"] as? JsonObject, "currentModeId")
        val catalog = AntigravityCatalog().of(AgyRosterReader().of(answer, AGY_WINDOW))
        return AgyOpen.Ready(AgySession(agy, id, AgySetup(options, mode, catalog, PromptBlocks(images))))
    }

    private fun unusable(answer: AcpAnswer): AgyOpen =
        AgyOpen.Unusable(answer.spoken("agy refused:") ?: AGY_SIGN_IN)

    /** [method] with [params], answered within this opener's [ms] when it has one — said so when none came in time. */
    private suspend fun asked(method: String, params: JsonObject): AcpAnswer =
        if (ms == null) {
            agy.request(method, params)
        } else {
            withTimeoutOrNull(ms) { agy.request(method, params) }
                ?: AcpAnswer.Refused(0, "no answer to $method in time")
        }
}

/** What a person whose agy is signed in to no account is told. */
internal const val AGY_SIGN_IN = "agy is signed in to no Google account. Run `agy acp --login` in a terminal, " +
    "then start a new session."
