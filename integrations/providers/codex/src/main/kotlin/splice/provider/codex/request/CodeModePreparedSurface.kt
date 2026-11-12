package splice.provider.codex.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.parse.AnthropicTurnBody
import splice.provider.codex.CodexCodeModeBridge
import splice.upstream.BuiltTurn

/** A preferred unavailable mode preserves the dialect's direct request bytes and keeps refusal handling armed. */
internal object CodeModePreparedSurface {
    fun prepare(
        manager: CodexCodeModeBridge,
        body: AnthropicTurnBody,
        built: BuiltTurn,
        turn: CodexCodeModeBridge.Turn,
        disableParallel: Boolean,
    ): BuiltTurn {
        val active = manager.availability(turn.toolPolicy).codeMode
        val request = if (active) {
            // Initial declarations remain historical. Current nested membership is enforced by turn.tools.
            val surface = manager.injectTool(
                built.requestBody,
                body.typed.tools.map { it.name }.toSet(),
                turn.toolPolicy,
            )
            val choice = JsonPrimitive(if (body.typed.toolChoice?.type == "any") "required" else "auto")
            CodexCodeModeInstructions.append(JsonObject(surface + ("tool_choice" to choice)), disableParallel)
        } else {
            built.requestBody
        }
        return built.copy(
            meta = built.meta.copy(tools = built.meta.tools.copy(codeMode = active)),
            requestBody = request,
            roundInterceptor = manager.interceptor(turn = turn, disableParallel = disableParallel),
        )
    }
}
