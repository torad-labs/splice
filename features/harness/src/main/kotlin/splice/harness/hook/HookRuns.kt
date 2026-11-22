// NEW: a mode's hooks at one moment (docs/specs/harness.md; modes/README.md, "Hooks"). They run in the order the
// manifests decide, each under its own time limit, and a failure lets the turn go on unless its manifest says closed.
package splice.harness.hook

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    /** Wraps [inner] with [HookRounds] when [session] has any `on_stop` or `response` hooks, else returns [inner]. */
    fun wrapRounds(
        session: AgentSession?,
        inner: splice.upstream.RoundInterceptor?,
        ask: splice.harness.agent.AgentAsk,
    ): splice.upstream.RoundInterceptor? {
        val s = session ?: return inner
        val has = at(HookMoment.ON_STOP, s).isNotEmpty() || at(HookMoment.RESPONSE, s).isNotEmpty()
        return if (has) HookRounds(s, this, inner, ask) else inner
    }

    /** Runs `before_tool_call` hooks for one tool call; `Rewrite` chains input, `Refuse` stops. */
    suspend fun beforeToolCall(session: AgentSession, tool: String, input: JsonElement): ToolBefore {
        val specs = at(HookMoment.BEFORE_TOOL_CALL, session)
        if (specs.isEmpty()) return ToolBefore.Go(input)
        var current = input
        for (spec in specs) {
            val payload = buildJsonObject {
                put("tool", tool)
                put("input", current)
            }
            when (val ans = one(spec, HookMoment.BEFORE_TOOL_CALL, session, payload)) {
                is HookAnswer.Refuse -> return ToolBefore.Refused(spec.mode, ans.reason)
                is HookAnswer.Rewrite -> current = ans.body
                else -> Unit
            }
        }
        return ToolBefore.Go(current)
    }

    /** Runs `after_tool_call` hooks for one tool call; `Replace` chains result text, `Refuse` stops. */
    suspend fun afterToolCall(
        session: AgentSession,
        tool: String,
        input: JsonElement,
        text: String,
        isError: Boolean,
    ): ToolAfter {
        val specs = at(HookMoment.AFTER_TOOL_CALL, session)
        if (specs.isEmpty()) return ToolAfter.Go(text, replaced = false)
        var current = text
        var replaced = false
        for (spec in specs) {
            val payload = buildJsonObject {
                put("tool", tool)
                put("input", input)
                put("result", current)
                put("is_error", isError)
            }
            when (val ans = one(spec, HookMoment.AFTER_TOOL_CALL, session, payload)) {
                is HookAnswer.Refuse -> return ToolAfter.Refused(spec.mode, ans.reason)
                is HookAnswer.Replace -> {
                    current = ans.text
                    replaced = true
                }
                else -> Unit
            }
        }
        return ToolAfter.Go(current, replaced)
    }

    /** Runs `response` hooks over the model's assembled content blocks. */
    suspend fun response(session: AgentSession, blocks: List<JsonObject>): ResponseDecision {
        val specs = at(HookMoment.RESPONSE, session)
        if (specs.isEmpty()) return ResponseDecision.Pass
        var body = buildJsonObject { put("content", JsonArray(blocks)) }
        var rewritten = false
        for (spec in specs) {
            when (val ans = one(spec, HookMoment.RESPONSE, session, body)) {
                is HookAnswer.Refuse -> return ResponseDecision.Refused(spec.mode, ans.reason)
                is HookAnswer.Rewrite -> {
                    body = ans.body
                    rewritten = true
                }
                else -> Unit
            }
        }
        return if (rewritten) ResponseDecision.Rewritten(body) else ResponseDecision.Pass
    }

    /** Runs `on_stop` hooks when the model finishes with no tool calls. */
    suspend fun onStop(session: AgentSession, blocks: List<JsonObject>, text: String): StopDecision {
        val specs = at(HookMoment.ON_STOP, session)
        val payload = buildJsonObject {
            put("content", JsonArray(blocks))
            put("text", text)
        }
        var decision: StopDecision = StopDecision.Pass
        for (spec in specs) {
            when (val ans = one(spec, HookMoment.ON_STOP, session, payload)) {
                is HookAnswer.Refuse -> return StopDecision.Refused(spec.mode, ans.reason)
                is HookAnswer.Continue -> return StopDecision.Continue(ans.text)
                is HookAnswer.Replace -> decision = StopDecision.Replace(ans.text)
                else -> Unit
            }
        }
        return decision
    }

    /** What the request hooks leave a turn: the body to send (null leaves it as it was), and the mode and reason of a
     *  refusal. */
    data class Hooked(val body: JsonObject?, val refusal: String?, val refusedBy: String?)

    sealed class ToolBefore {
        data class Go(val input: JsonElement) : ToolBefore()
        data class Refused(val mode: String, val reason: String) : ToolBefore()
    }

    sealed class ToolAfter {
        data class Go(val text: String, val replaced: Boolean) : ToolAfter()
        data class Refused(val mode: String, val reason: String) : ToolAfter()
    }

    sealed class ResponseDecision {
        data object Pass : ResponseDecision()
        data class Rewritten(val body: JsonObject) : ResponseDecision()
        data class Refused(val mode: String, val reason: String) : ResponseDecision()
    }

    sealed class StopDecision {
        data object Pass : StopDecision()
        data class Continue(val text: String) : StopDecision()
        data class Replace(val text: String) : StopDecision()
        data class Refused(val mode: String, val reason: String) : StopDecision()
    }
}
