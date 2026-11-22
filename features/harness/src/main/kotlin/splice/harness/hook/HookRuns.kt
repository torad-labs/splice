// NEW: a mode's hooks at one moment (docs/specs/harness.md; modes/README.md, "Hooks"). They run in the order the
// manifests decide, each under its own time limit, and a failure lets the turn go on unless its manifest says closed.
package splice.harness.hook

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.capability.CapabilityWorker
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.harness.HttpWorker
import splice.harness.McpToolCall
import splice.harness.McpWorker
import splice.harness.WorkerEnd
import splice.harness.WorkerProgress
import splice.harness.agent.AgentSession
import java.nio.file.Path

/** What one moment's hooks came to: each hook with its answer, in order, and the mode and reason of the first refusal. */
internal data class HookBatch(
    val answers: List<Pair<HookSpec, HookAnswer>>,
    val refusal: String? = null,
    val refusedBy: String? = null,
)

/** A worker that could not answer its hook. */
internal class HookFailure(message: String) : Exception(message)

/** Runs the hooks of one moment for [head], through [port] and the harness's own workers. */
internal class HookRuns(
    private val head: String,
    private val port: ModeHookPort,
    private val http: HttpWorker,
    private val mcp: McpWorker,
    private val log: LogSink,
) {
    private val wire = HookWire()

    /** The hooks of [moment] on for [session], in the order the manifests decide, ties by mode name. */
    fun at(moment: HookMoment, session: AgentSession): List<HookSpec> =
        port.hooks(session).filter { it.moment == moment }.sortedWith(compareBy({ it.order }, { it.mode }))

    /** Runs [moment]'s hooks in order. A refusal ends the run; the answers before it are kept. */
    suspend fun run(moment: HookMoment, session: AgentSession, body: JsonObject): HookBatch {
        val answers = mutableListOf<Pair<HookSpec, HookAnswer>>()
        for (spec in at(moment, session)) {
            val answer = one(spec, moment, session, body)
            if (answer is HookAnswer.Refuse) return HookBatch(answers, answer.reason, spec.mode)
            answers += spec to answer
        }
        return HookBatch(answers)
    }

    /** What the request hooks make of a turn: the model a session-start hook chose set in [original], then the rewrite
     *  a request hook made, or the reason one refused. [sending] is the body the agent itself changed, if any. */
    suspend fun request(session: AgentSession?, original: JsonObject, sending: JsonObject?, chosen: String?): Hooked {
        val modelled = chosen?.let { JsonObject(original + (HOOK_MODEL_KEY to JsonPrimitive(it))) }
        val body = modelled ?: sending
        val batch = session?.let { run(HookMoment.REQUEST, it, body ?: original) }
        val refusal = batch?.refusal
        if (refusal != null) return Hooked(null, refusal, batch.refusedBy ?: "a mode")
        val rewritten = batch?.answers.orEmpty().map { it.second }.filterIsInstance<HookAnswer.Rewrite>().lastOrNull()
        return Hooked(rewritten?.body ?: body, null, null)
    }

    private suspend fun one(spec: HookSpec, moment: HookMoment, session: AgentSession, body: JsonObject): HookAnswer {
        val call = HookCall(moment, session, body)
        val attempt = Cancellables.runCatchingBestEffort {
            withTimeoutOrNull(spec.timeoutMs) { work(spec, moment, session, body, call) }
        }
        val answer = attempt.getOrElse { failure ->
            return couldNot(spec, moment, "failed: ${SafeFailureText.render(failure)}")
        } ?: return couldNot(spec, moment, "timed out after ${spec.timeoutMs} ms")
        val valid = wire.valid(answer, moment)
        if (valid == null) {
            log("[$head] mode ${spec.mode}'s ${moment.wire} hook answered for another moment; the turn goes on\n")
        }
        return valid ?: HookAnswer.Pass
    }

    private suspend fun work(
        spec: HookSpec,
        moment: HookMoment,
        session: AgentSession,
        body: JsonObject,
        call: HookCall,
    ): HookAnswer = when (val worker = spec.worker) {
        is HookWorker.InProcess -> worker.run.run(call)
        is HookWorker.Http -> {
            val target = CapabilityWorker.Http(worker.url, worker.authEnv)
            ended(http.call(target, payload(moment, session, body), WorkerProgress { }), spec.mode)
        }
        is HookWorker.Mcp -> {
            val target = CapabilityWorker.Mcp(worker.server, worker.tool)
            val tool = McpToolCall(payload(moment, session, body), meta(moment, session, spec.mode))
            val answered = mcp.call(target, head, session.folder?.let(Path::of), tool, WorkerProgress { })
            ended(answered, spec.mode)
        }
    }

    private fun ended(end: WorkerEnd, mode: String): HookAnswer = when (end) {
        is WorkerEnd.Result -> wire.answer(end.text, mode)
        is WorkerEnd.Refusal -> HookAnswer.Refuse(end.reason)
        is WorkerEnd.Failed -> throw HookFailure(end.why)
    }

    /** A hook that could not answer: refused when its manifest says closed, else said in the log and passed. */
    private fun couldNot(spec: HookSpec, moment: HookMoment, why: String): HookAnswer {
        if (spec.failClosed) return HookAnswer.Refuse("mode ${spec.mode} could not run its ${moment.wire} hook: $why")
        log("[$head] mode ${spec.mode}'s ${moment.wire} hook $why; the turn goes on\n")
        return HookAnswer.Pass
    }

    private fun payload(moment: HookMoment, session: AgentSession, body: JsonObject): JsonObject = buildJsonObject {
        put("moment", moment.wire)
        put(
            "session",
            buildJsonObject {
                put("id", session.id)
                put("folder", session.folder)
                put("head", session.head)
                put("model", session.model)
                put("caller", session.caller)
            },
        )
        put("body", body)
    }

    private fun meta(moment: HookMoment, session: AgentSession, mode: String): JsonObject = buildJsonObject {
        put("mode", mode)
        put("moment", moment.wire)
        put("session", session.id)
    }

    /** What the request hooks leave a turn: the body to send (null leaves it as it was), and the mode and reason of a
     *  refusal. */
    data class Hooked(val body: JsonObject?, val refusal: String?, val refusedBy: String?)
}
