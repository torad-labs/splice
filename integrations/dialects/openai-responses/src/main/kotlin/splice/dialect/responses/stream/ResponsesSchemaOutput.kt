// NEW: strict schema response text is the caller's StructuredOutput input, not a prose answer.
package splice.dialect.responses.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.request.ResponsesStrictSchema
import splice.upstream.sse.WireSink
import java.util.UUID

/** Relays only the upstream's real, completed JSON. The client's schema validator still receives the tool input. */
internal class ResponsesSchemaOutput(
    private val toolName: String?,
    /** The caller's schema, when the backend answered a stricter form of it ([ResponsesStrictSchema]). */
    private val answerSchema: JsonObject? = null,
) {
    private val customCalls = ResponsesCustomCallParse()

    suspend fun emit(state: ResponsesTurnState, sink: WireSink) {
        if (toolName == null || !completed(state)) return
        if (state.textBuf.isEmpty()) {
            if (!endedInTools(state)) state.toolArgsInvalid = "empty structured output"
            return
        }
        val text = state.textBuf.toString()
        val invalid = ResponsesFrameParse().invalidToolArgsReason(text)
            ?: if (Json.parseToJsonElement(text) is JsonObject) null else "structured tool input is not an object"
        if (invalid != null) {
            state.toolArgsInvalid = invalid
        } else {
            // A wire id identifies this translated tool call, never an invented upstream call or cache key.
            val block = sink.openTool("toolu_schema_${UUID.randomUUID()}", toolName)
            sink.inputJsonDelta(block, input(text))
            sink.closeBlock(block)
            state.hasToolUse = true
            state.schemaToolEmitted = true
        }
    }

    /** The tool input for [text]: brought back to the caller's schema when the backend answered a stricter form. */
    private fun input(text: String): String {
        val schema = answerSchema ?: return text
        return ResponsesStrictSchema.restore(Json.parseToJsonElement(text), schema).toString()
    }

    /** A cancelled/failed sink cannot turn buffered schema text into a clean completion without delivering its tool. */
    fun latchUndelivered(state: ResponsesTurnState) {
        if (toolName == null) return
        if (state.incomplete && state.toolArgsInvalid == null) state.toolArgsInvalid = "incomplete structured output"
        if (completed(state) && missingDelivery(state)) {
            state.toolArgsInvalid = "structured output was not delivered as tool input"
        }
    }

    private fun missingDelivery(state: ResponsesTurnState): Boolean =
        !state.schemaToolEmitted && (state.textBuf.isNotEmpty() || !endedInTools(state))

    /** A round with no schema JSON is not empty when the model spent it on tools: a function call, a completed custom
     *  call (code mode's `exec`, the round a Claude Code client under StructuredOutput takes before its JSON), or
     *  reasoning alone, which has its own continuation. The custom calls are read as the outcome reads them, the
     *  terminal response's and the streamed ones, so a round the stream closed early is not called empty. */
    private fun endedInTools(state: ResponsesTurnState): Boolean =
        state.hasToolUse || state.endedInReasoning ||
            customCalls.merge(state.customCalls, state.finalResponse).isNotEmpty()

    private fun completed(state: ResponsesTurnState): Boolean =
        state.finalResponse != null && !state.incomplete && state.upstreamFailure == null &&
            state.refusalBuf.isEmpty() && state.toolArgsInvalid == null
}
