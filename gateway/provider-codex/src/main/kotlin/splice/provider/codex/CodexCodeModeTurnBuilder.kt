// NEW: arms code mode only for eligible Codex Responses-lite turns and extracts owned results.
package splice.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.parse.AnthropicTurnBody
import splice.core.util.JsonScalars
import splice.core.wire.TextBlock
import splice.core.wire.ToolResultBlock
import splice.spi.BuiltTurn
import splice.spi.CodeModeResult

internal class CodexCodeModeTurnBuilder(private val bridge: CodexCodeModeBridge?) {
    fun prepare(body: AnthropicTurnBody, compact: Boolean, sessionId: String?, built: BuiltTurn): BuiltTurn {
        val manager = bridge ?: return built
        if (!eligible(body, compact, built)) return built
        require(body.typed.tools.none { it.name == CODE_MODE_TOOL_NAME }) {
            "client tool name '$CODE_MODE_TOOL_NAME' collides with splice's code-mode bridge"
        }
        require(!sessionId.isNullOrBlank()) { "code mode requires a client session id" }
        val turn = CodexCodeModeBridge.Turn(
            sessionId = sessionId,
            conversationKey = built.meta.conversationKey.orEmpty(),
            model = built.meta.upstreamModel,
            tools = body.typed.tools.map { it.name }.toSet(),
            toolResults = toolResults(body),
        )
        val disableParallel = body.typed.toolChoice?.disableParallelToolUse == true
        return built.copy(
            requestBody = CodexCodeModeInstructions.append(manager.injectTool(built.requestBody), disableParallel),
            roundInterceptor = manager.interceptor(turn = turn, disableParallel = disableParallel),
        )
    }

    private fun eligible(body: AnthropicTurnBody, compact: Boolean, built: BuiltTurn): Boolean {
        val choice = body.typed.toolChoice
        val choiceAllowsBridge = choice == null || (choice.name == null && choice.type in setOf("auto", "any"))
        return !compact &&
            body.typed.tools.isNotEmpty() &&
            choiceAllowsBridge &&
            CODE_MODE_MODEL.matches(built.meta.upstreamModel) &&
            isLiteRequest(built.requestBody)
    }

    private fun isLiteRequest(request: JsonObject): Boolean {
        val input = request[FIELD_INPUT] as? JsonArray ?: return false
        val first = input.firstOrNull() as? JsonObject ?: return false
        return request[FIELD_TOOLS] == null &&
            JsonScalars.str(first, FIELD_TYPE) == TYPE_ADDITIONAL_TOOLS &&
            first[FIELD_TOOLS] is JsonArray
    }

    private fun toolResults(body: AnthropicTurnBody): List<CodeModeResult> = body.typed.messages
        .flatMap { it.content }
        .filterIsInstance<ToolResultBlock>()
        .filter { it.toolUseId.startsWith(CODE_MODE_CLIENT_ID_PREFIX) }
        .map { block ->
            CodeModeResult(
                id = block.toolUseId,
                // V4-114: the refusal NARROWS instead of asserting. `require(part is TextBlock)`
                // smart-casts, so the text read is checked by the compiler; the previous shape
                // tested every part in one `all {}` and then re-asserted each with `as TextBlock`,
                // two statements that had to agree with nothing holding them together — and a new
                // ContentBlock subtype would have arrived as a ClassCastException on the turn path.
                // Same refusal, same type (IllegalArgumentException), same message.
                output = block.content.joinToString("") { part ->
                    require(part is TextBlock) {
                        "code-mode tool result '${block.toolUseId}' contains unsupported non-text content"
                    }
                    part.text
                },
                isError = block.isError == true,
            )
        }
}

private const val FIELD_INPUT = "input"
private const val FIELD_TOOLS = "tools"
private const val FIELD_TYPE = "type"
private const val TYPE_ADDITIONAL_TOOLS = "additional_tools"
private val CODE_MODE_MODEL = Regex("^gpt-6-(astra|sol)(?:\\[\\d+[km]])?$", RegexOption.IGNORE_CASE)
