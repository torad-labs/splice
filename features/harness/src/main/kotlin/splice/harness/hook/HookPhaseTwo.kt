// NEW: Phase 2 hook dispatchers (`before_tool_call`, `after_tool_call`, `response`, `on_stop`), split from HookRuns
// to keep both files focused and well below the concentration ceiling.
package splice.harness.hook

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.harness.agent.AgentSession

internal class HookPhaseTwo(private val runs: HookRuns) {
    suspend fun beforeToolCall(session: AgentSession, tool: String, input: JsonElement): HookRuns.ToolBefore {
        val specs = runs.at(HookMoment.BEFORE_TOOL_CALL, session)
        if (specs.isEmpty()) return HookRuns.ToolBefore.Go(input)
        var current = input
        for (spec in specs) {
            val payload = buildJsonObject {
                put("tool", tool)
                put("input", current)
            }
            when (val ans = runs.one(spec, HookMoment.BEFORE_TOOL_CALL, session, payload)) {
                is HookAnswer.Refuse -> return HookRuns.ToolBefore.Refused(spec.mode, ans.reason)
                is HookAnswer.Rewrite -> current = ans.body
                HookAnswer.Pass,
                is HookAnswer.AddContext,
                is HookAnswer.ChooseModel,
                is HookAnswer.Replace,
                is HookAnswer.Continue,
                -> Unit
            }
        }
        return HookRuns.ToolBefore.Go(current)
    }

    suspend fun afterToolCall(
        session: AgentSession,
        tool: String,
        input: JsonElement,
        text: String,
        isError: Boolean,
    ): HookRuns.ToolAfter {
        val specs = runs.at(HookMoment.AFTER_TOOL_CALL, session)
        if (specs.isEmpty()) return HookRuns.ToolAfter.Go(text, replaced = false)
        var current = text
        var replaced = false
        for (spec in specs) {
            val payload = buildJsonObject {
                put("tool", tool)
                put("input", input)
                put("result", current)
                put("is_error", isError)
            }
            when (val ans = runs.one(spec, HookMoment.AFTER_TOOL_CALL, session, payload)) {
                is HookAnswer.Refuse -> return HookRuns.ToolAfter.Refused(spec.mode, ans.reason)
                is HookAnswer.Replace -> {
                    current = ans.text
                    replaced = true
                }
                HookAnswer.Pass,
                is HookAnswer.AddContext,
                is HookAnswer.ChooseModel,
                is HookAnswer.Rewrite,
                is HookAnswer.Continue,
                -> Unit
            }
        }
        return HookRuns.ToolAfter.Go(current, replaced)
    }

    suspend fun response(session: AgentSession, blocks: List<JsonObject>): HookRuns.ResponseDecision {
        val specs = runs.at(HookMoment.RESPONSE, session)
        if (specs.isEmpty()) return HookRuns.ResponseDecision.Pass
        var body = buildJsonObject { put("content", JsonArray(blocks)) }
        var rewritten = false
        for (spec in specs) {
            when (val ans = runs.one(spec, HookMoment.RESPONSE, session, body)) {
                is HookAnswer.Refuse -> return HookRuns.ResponseDecision.Refused(spec.mode, ans.reason)
                is HookAnswer.Rewrite -> {
                    body = ans.body
                    rewritten = true
                }
                HookAnswer.Pass,
                is HookAnswer.AddContext,
                is HookAnswer.ChooseModel,
                is HookAnswer.Replace,
                is HookAnswer.Continue,
                -> Unit
            }
        }
        return if (rewritten) HookRuns.ResponseDecision.Rewritten(body) else HookRuns.ResponseDecision.Pass
    }

    suspend fun onStop(session: AgentSession, blocks: List<JsonObject>, text: String): HookRuns.StopDecision {
        val specs = runs.at(HookMoment.ON_STOP, session)
        val payload = buildJsonObject {
            put("content", JsonArray(blocks))
            put("text", text)
        }
        var decision: HookRuns.StopDecision = HookRuns.StopDecision.Pass
        for (spec in specs) {
            when (val ans = runs.one(spec, HookMoment.ON_STOP, session, payload)) {
                is HookAnswer.Refuse -> return HookRuns.StopDecision.Refused(spec.mode, ans.reason)
                is HookAnswer.Continue -> return HookRuns.StopDecision.Continue(ans.text)
                is HookAnswer.Replace -> decision = HookRuns.StopDecision.Replace(ans.text)
                HookAnswer.Pass,
                is HookAnswer.AddContext,
                is HookAnswer.ChooseModel,
                is HookAnswer.Rewrite,
                -> Unit
            }
        }
        return decision
    }
}
