// NEW: arms code mode only for eligible Codex Responses-lite turns and extracts owned results.
package splice.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.parse.AnthropicTurnBody
import splice.core.util.JsonScalars
import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
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

    /** Internal, not private: V4-178 pins the marker on the parsed result, and the interceptor the
     *  turn rides in is an opaque lambda a test cannot look inside. */
    internal fun toolResults(body: AnthropicTurnBody): List<CodeModeResult> = body.typed.messages
        .flatMap { it.content }
        .filterIsInstance<ToolResultBlock>()
        .filter { it.toolUseId.startsWith(CODE_MODE_CLIENT_ID_PREFIX) }
        .map { block ->
            CodeModeResult(
                id = block.toolUseId,
                output = block.content.joinToString("") { part -> resultText(block.toolUseId, part) },
                isError = block.isError == true,
            )
        }

    /** V4-178: what a splice_exec script reads back for one part of a client tool result.
     *
     *  Text is the text. Anything else is a MARKER, never a refusal and never a silent drop: this
     *  was `require(part is TextBlock)` (V4-114, chosen over a `filterIsInstance` that would have
     *  shipped the text half as the whole result), and the refusal reached Claude Code as an error
     *  terminal on a screenshot that agent-browser returned inside a code-mode call — and then on
     *  every turn after it, because the image stays in the client's history until compaction
     *  drops it (session 22d0cee0, 2026-09-20). A wedge is worse than either shape V4-114 weighed.
     *
     *  The marker names the media and the size so the model knows what it did not get, and says
     *  why in words it can act on: function_call_output.output is string-only and a script return
     *  is a string, so pixels cannot ride this path. Carrying them in a follow-up input_image
     *  message, as the ordinary tool_result path does (ResponsesInputTools.appendToolResult), is
     *  the passthrough row, not this one. */
    private fun resultText(toolUseId: String, part: ContentBlock): String = when (part) {
        is TextBlock -> part.text
        is ImageBlock -> omitted(toolUseId, "image", part.source)
        is DocumentBlock -> omitted(toolUseId, "document", part.source)
        else -> omitted(toolUseId, "non-text content", null)
    }

    private fun omitted(toolUseId: String, kind: String, source: MediaSource?): String {
        val media = source?.mediaType?.takeIf { it.isNotEmpty() } ?: kind
        val size = (source?.data?.length?.let { ", $it base64 chars" } ?: source?.url?.let { ", url $it" }).orEmpty()
        return "[$kind omitted by splice code-mode from tool_result $toolUseId: $media$size — a " +
            "splice_exec script reads tool results as text only; call the tool outside code mode to see it]"
    }
}

private const val FIELD_INPUT = "input"
private const val FIELD_TOOLS = "tools"
private const val FIELD_TYPE = "type"
private const val TYPE_ADDITIONAL_TOOLS = "additional_tools"
private val CODE_MODE_MODEL = Regex("^gpt-6-(astra|sol)(?:\\[\\d+[km]])?$", RegexOption.IGNORE_CASE)
