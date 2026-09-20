// NEW: arms code mode only for eligible Codex Responses-lite turns and extracts owned results.
package splice.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.parse.AnthropicTurnBody
import splice.core.util.JsonScalars
import splice.core.wire.ContentBlock
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.MediaSource
import splice.core.wire.TextBlock
import splice.core.wire.ToolResultBlock
import splice.dialect.responses.ImageDisposition
import splice.dialect.responses.ResponsesToolResultMedia
import splice.spi.BuiltTurn
import splice.spi.CodeModeResult

/** [media] is the dialect's own tool_result image renderer (V4-179): the bridge renders a result's
 *  follow-ups ONCE with the same policy the ordinary path applies, persists them on the record and
 *  replays them from there — so what the model sees for a screenshot inside a script is exactly
 *  what it would see for one outside it, and in the same place. */
internal class CodexCodeModeTurnBuilder(
    private val bridge: CodexCodeModeBridge?,
    private val media: ResponsesToolResultMedia,
) {
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
            toolMedia = toolMedia(body),
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
    internal fun toolResults(body: AnthropicTurnBody): List<CodeModeResult> = bridgeResults(body).map { block ->
        val dispositions = media.followUps(block).dispositions.iterator()
        CodeModeResult(
            id = block.toolUseId,
            output = block.content.joinToString("") { part -> resultText(block.toolUseId, part, dispositions) },
            isError = block.isError == true,
        )
    }

    /** V4-179: each bridge result's follow-up items, rendered once here and nowhere else. */
    internal fun toolMedia(body: AnthropicTurnBody): Map<String, List<JsonElement>> =
        bridgeResults(body).associate { block -> block.toolUseId to media.followUps(block).items }

    private fun bridgeResults(body: AnthropicTurnBody): List<ToolResultBlock> = body.typed.messages
        .flatMap { it.content }
        .filterIsInstance<ToolResultBlock>()
        .filter { it.toolUseId.startsWith(CODE_MODE_CLIENT_ID_PREFIX) }

    /** V4-178: what a splice_exec script reads back for one part of a client tool result.
     *
     *  Text is the text. Anything else is a MARKER, never a refusal and never a silent drop: this
     *  was `require(part is TextBlock)` (V4-114, chosen over a `filterIsInstance` that would have
     *  shipped the text half as the whole result), and the refusal reached Claude Code as an error
     *  terminal on a screenshot that agent-browser returned inside a code-mode call — and then on
     *  every turn after it, because the image stays in the client's history until compaction
     *  drops it (session 22d0cee0, 2026-09-20). A wedge is worse than either shape V4-114 weighed.
     *
     *  V4-179: the marker now tells the truth about where the pixels WENT. [dispositions] is the
     *  renderer's verdict per image, in content order: a delivered image reached the model beside
     *  this script's output, and the marker says so; an omitted one carries the renderer's own
     *  reason (unsupported source, below the vendor floor), in the renderer's words. Either way the
     *  script itself reads text only — function_call_output.output is string-only and a script
     *  return is a string. */
    private fun resultText(toolUseId: String, part: ContentBlock, dispositions: Iterator<ImageDisposition>): String =
        when (part) {
            is TextBlock -> part.text
            is ImageBlock -> imageMarker(toolUseId, part.source, dispositions.next())
            is DocumentBlock -> omitted(toolUseId, "document", part.source, DOCUMENT_REASON)
            else -> omitted(toolUseId, "non-text content", null, DOCUMENT_REASON)
        }

    private fun imageMarker(toolUseId: String, source: MediaSource?, disposition: ImageDisposition): String =
        if (disposition.delivered) {
            "[image from tool_result $toolUseId: ${describe("image", source)} — delivered to the model " +
                "beside this script's output; a splice_exec script itself reads tool results as text only]"
        } else {
            omitted(toolUseId, "image", source, disposition.reason.orEmpty())
        }

    private fun omitted(toolUseId: String, kind: String, source: MediaSource?, why: String): String =
        "[$kind omitted by splice code-mode from tool_result $toolUseId: ${describe(kind, source)} — $why; " +
            "a splice_exec script reads tool results as text only]"

    private fun describe(kind: String, source: MediaSource?): String {
        val media = source?.mediaType?.takeIf { it.isNotEmpty() } ?: kind
        val size = (source?.data?.length?.let { ", $it base64 chars" } ?: source?.url?.let { ", url $it" }).orEmpty()
        return "$media$size"
    }
}

/** A document (or an unknown block) has no wire shape on this path at all — not the ordinary one
 *  either — so its marker names that rather than a delivery that did not happen. */
private const val DOCUMENT_REASON = "this content type cannot ride to the model on this path"

private const val FIELD_INPUT = "input"
private const val FIELD_TOOLS = "tools"
private const val FIELD_TYPE = "type"
private const val TYPE_ADDITIONAL_TOOLS = "additional_tools"
private val CODE_MODE_MODEL = Regex("^gpt-6-(astra|sol)(?:\\[\\d+[km]])?$", RegexOption.IGNORE_CASE)
