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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.util.JsonScalars
import splice.core.util.LogSink

/** Capabilities learned from agy's `initialize` handshake: whether its prompts take [images], and whether a new
 *  session takes text to [append] to its system prompt (`_meta.antigravity.systemPrompt.append`). */
internal data class AgyHello(val images: Boolean, val append: Boolean = false)

/** Result of opening an agy session with optional MCP servers. */
internal data class OpenedSession(
    val open: AgyOpen,
    val resumed: Boolean,
    val mcpApplied: Boolean,
)

/** What opening agy came to. */
internal sealed class AgyOpen {
    data class Ready(val session: AgySession) : AgyOpen()

    /** agy can run no turn, and what to tell the person. */
    data class Unusable(val reason: String) : AgyOpen()
}

/** Opens an agy session on [agy] in folder [cwd]; each ask waits at most [ms] when said so, and as long as it takes
 *  otherwise. A new session (never one taken up again) gets [append] added to agy's system prompt when given. */
internal class AgyOpener(
    private val agy: AcpConnection,
    private val cwd: String,
    private val ms: Long? = null,
    private val append: String? = null,
) {
    private val requests = AcpRequests()

    /** Session [resume] taken up again, or a new one when there is none to take up or agy does not know it. */
    suspend fun open(resume: String?): AgyOpen {
        val (hello, refusal) = sayHello()
        if (hello == null) return AgyOpen.Unusable(refusal ?: AGY_SIGN_IN)
        return openSession(resume, hello, JsonArray(emptyList()), emptyList(), LogSink {}).open
    }

    /** Performs ACP `initialize`; returns capabilities or the reason agy cannot be used. */
    suspend fun sayHello(): Pair<AgyHello?, String?> {
        val hello = hello()
        if (hello !is AcpAnswer.Result) return null to (hello.spoken("agy refused:") ?: AGY_SIGN_IN)
        val capabilities = hello.result["agentCapabilities"] as? JsonObject
        val prompts = capabilities?.get("promptCapabilities") as? JsonObject
        val antigravity = (capabilities?.get("_meta") as? JsonObject)?.get("antigravity") as? JsonObject
        val append = (antigravity?.get("systemPrompt") as? JsonObject)?.get("append") as? JsonPrimitive
        val images = JsonScalars.str(prompts, "image") == "true"
        // Only a JSON `true` offers it: a string, `false` or nothing leaves agy's system prompt alone.
        return AgyHello(images, append?.takeUnless { it.isString }?.booleanOrNull == true) to null
    }

    /** Opens [resume] or a new session with [mcpServers]; if agy refuses the MCP servers, logs [serverNames] and
     *  retries with an empty list so the session still runs. */
    suspend fun openSession(
        resume: String?,
        hello: AgyHello,
        mcpServers: JsonArray,
        serverNames: List<String>,
        log: LogSink,
    ): OpenedSession {
        val (attempt, refusedMcp) = tryOpenWithFallback(resume, mcpServers, serverNames, log)
        val result = (attempt.answer as? AcpAnswer.Result)?.result
            ?: return OpenedSession(unusable(attempt.answer), resumed = false, mcpApplied = false)
        val open = attempt.id?.let { ready(it, result, hello.images) }
            ?: AgyOpen.Unusable("agy opened no session")
        val applied = open is AgyOpen.Ready && mcpServers.isNotEmpty() && !refusedMcp
        return OpenedSession(open, resumed = attempt.resumed, mcpApplied = applied)
    }

    private suspend fun tryOpenWithFallback(
        resume: String?,
        mcpServers: JsonArray,
        serverNames: List<String>,
        log: LogSink,
    ): Pair<OpenAttempt, Boolean> {
        val first = openAttempt(resume, mcpServers)
        val answer = first.answer
        if (mcpServers.isNotEmpty() && isMcpRefusal(answer)) {
            val message = (answer as? AcpAnswer.Refused)?.message.orEmpty()
            val names = serverNames.joinToString(", ")
            log("[antigravity] agy refused MCP servers ($names): $message; retrying without\n")
            return openAttempt(resume, JsonArray(emptyList())) to true
        }
        return first to false
    }

    private fun isMcpRefusal(answer: AcpAnswer): Boolean = answer is AcpAnswer.Refused && answer.code != AUTH_REQUIRED

    /** agy's answer to `initialize`. */
    suspend fun hello(): AcpAnswer = asked("initialize", requests.initialize())

    /** The session [resume] when agy takes it up (or refuses it for want of a sign-in), else a new one: its id and
     *  everything agy answered with; whatever went wrong instead is in that answer too. */
    suspend fun opened(resume: String?): Pair<String?, AcpAnswer> {
        val attempt = openAttempt(resume, JsonArray(emptyList()))
        return attempt.id to attempt.answer
    }

    private suspend fun openAttempt(resume: String?, mcpServers: JsonArray): OpenAttempt {
        val resumed = resume?.let { asked("session/resume", requests.resume(it, cwd, mcpServers)) }
        val signedOut = resumed is AcpAnswer.Refused && resumed.code == AUTH_REQUIRED
        if (resumed is AcpAnswer.Result) return OpenAttempt(resume, resumed, resumed = true)
        if (signedOut) return OpenAttempt(resume, resumed, resumed = false)
        val beganNew = asked("session/new", requests.newSession(cwd, mcpServers, append))
        val id = (beganNew as? AcpAnswer.Result)?.let { JsonScalars.str(it.result, "sessionId") }
        return OpenAttempt(id, beganNew, resumed = false)
    }

    private data class OpenAttempt(val id: String?, val answer: AcpAnswer, val resumed: Boolean)

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
