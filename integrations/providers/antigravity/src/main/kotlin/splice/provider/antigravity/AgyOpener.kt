// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). Opens the agy session a Desktop session
// runs on: `initialize`, then `session/resume` for a session Desktop takes up again, or `session/new`. A session agy
// does not know (one that began on Claude, decision 5) opens a new one instead of failing, and Desktop keeps its own id
// for it. A signed-out agy, or one that ends or refuses, leaves the session unusable with what to tell the person.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** What opening agy came to. */
internal sealed class AgyOpen {
    data class Ready(val session: AgySession) : AgyOpen()

    /** agy can run no turn, and what to tell the person. */
    data class Unusable(val reason: String) : AgyOpen()
}

/** Opens an agy session on [agy] in folder [cwd]. */
internal class AgyOpener(private val agy: AcpConnection, private val cwd: String) {
    private val requests = AcpRequests()

    /** Session [resume] taken up again, or a new one when there is none to take up or agy does not know it. */
    suspend fun open(resume: String?): AgyOpen {
        val hello = agy.request("initialize", requests.initialize())
        if (hello !is AcpAnswer.Result) return unusable(hello)
        val prompts = (hello.result["agentCapabilities"] as? JsonObject)?.get("promptCapabilities") as? JsonObject
        val images = JsonScalars.str(prompts, "image") == "true"
        val (id, answer) = opened(resume)
        val result = (answer as? AcpAnswer.Result)?.result ?: return unusable(answer)
        return id?.let { ready(it, result, images) } ?: AgyOpen.Unusable("agy opened no session")
    }

    /** The session [resume] when agy takes it up (or refuses it for want of a sign-in), else a new one: its id and
     *  agy's answer. */
    private suspend fun opened(resume: String?): Pair<String?, AcpAnswer> {
        val resumed = resume?.let { agy.request("session/resume", requests.resume(it, cwd)) }
        val signedOut = resumed is AcpAnswer.Refused && resumed.code == AUTH_REQUIRED
        val taken = resumed is AcpAnswer.Result || signedOut
        if (resumed != null && taken) return resume to resumed
        val opened = agy.request("session/new", requests.newSession(cwd))
        return (opened as? AcpAnswer.Result)?.let { JsonScalars.str(it.result, "sessionId") } to opened
    }

    private fun ready(id: String, answer: JsonObject, images: Boolean): AgyOpen {
        val options = answer["configOptions"] as? JsonArray ?: JsonArray(emptyList())
        val mode = JsonScalars.str(answer["modes"] as? JsonObject, "currentModeId")
        val catalog = AntigravityCatalog().of(AgyRosterReader().of(answer, AGY_WINDOW))
        return AgyOpen.Ready(AgySession(agy, id, AgySetup(options, mode, catalog, PromptBlocks(images))))
    }

    private fun unusable(answer: AcpAnswer): AgyOpen = AgyOpen.Unusable(
        when (answer) {
            is AcpAnswer.Refused -> if (answer.code == AUTH_REQUIRED) AGY_SIGN_IN else "agy refused: ${answer.message}"
            AcpAnswer.Ended -> "agy ended before it answered"
            is AcpAnswer.Result -> "agy answered out of turn"
        },
    )
}

/** What a person whose agy is signed in to no account is told. */
internal const val AGY_SIGN_IN = "agy is signed in to no Google account. Run `agy acp --login` in a terminal, " +
    "then start a new session."
