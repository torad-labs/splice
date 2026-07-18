// NEW: (no Node source): OpenAI Chat Completions SSE → Anthropic SSE via the shared WireSink.
// Chat streaming shape: each frame is {choices:[{delta:{content?, reasoning_content?,
// tool_calls?}, finish_reason?}], usage?}. Text opens lazily on first content delta; a
// reasoning_content field (DeepSeek-style) opens a thinking block; tool_calls stream by index
// (delta.tool_calls[i].function.arguments). Same honesty gates as Responses: no clean end on a
// failure, ClientAbandoned when the client is gone before any finish. finish_reason maps:
// tool_calls→hasToolUse, length→incomplete, stop→end_turn.
package splice.dialect.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.spi.StreamTranslator
import splice.spi.WatchdogFired
import splice.spi.WireSink
import java.io.IOException
import java.util.concurrent.CancellationException

public data class ChatTurnContext(
    val clientGone: () -> Boolean,
    val watchdogFired: () -> WatchdogFired?,
    val idleCapMs: Long,
    val totalCapMs: Long,
)

public class ChatStreamTranslator(private val ctx: ChatTurnContext) : StreamTranslator {

    private var textBlock: WireBlockIndex? = null
    private var thinkingBlock: WireBlockIndex? = null
    private val toolBlocks = HashMap<Int, WireBlockIndex>()
    private var hasToolUse = false
    private var emittedText = false
    private var incomplete = false
    private val textBuf = StringBuilder()
    private val thinkingBuf = StringBuilder()
    private var finished = false
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var failure: String? = null

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        try {
            upstream.collect { evt -> onEvent(evt, sink) }
        } catch (e: CancellationException) {
            // Only a watchdog fire may swallow cancellation; a real cancel propagates.
            if (ctx.watchdogFired() == null) throw e
        } catch (ignored: IOException) {
            // stream read error: surface via the honest terminal decision, never a crash
        } catch (ignored: SerializationException) {
            // malformed upstream frame: surface via the honest terminal decision
        } catch (ignored: IllegalArgumentException) {
            // malformed value in a frame: surface via the honest terminal decision
        }
        sink.closeAll()
        return terminalOutcome()
    }

    private fun terminalOutcome(): TurnOutcome =
        failure?.let { TurnOutcome.Failure(ErrorType.API_ERROR, "chat backend: $it") }
            ?: ctx.watchdogFired()?.let {
                TurnOutcome.Failure(ErrorType.OVERLOADED, "chat: upstream stalled — aborted; retry")
            }
            ?: if (!finished) unfinishedOutcome() else successOutcome()

    private fun unfinishedOutcome(): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned
        } else {
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                "chat: stream ended without a finish_reason (truncated); retry",
            )
        }

    private fun successOutcome(): TurnOutcome = TurnOutcome.Success(
        hasToolUse = hasToolUse,
        incomplete = incomplete,
        usage = Usage(inputTokens, outputTokens),
        thinkingText = thinkingBuf.toString(),
        bodyText = textBuf.toString(),
        emittedText = emittedText,
    )

    private suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        (evt["error"] as? JsonObject)?.let {
            failure = str(it["message"]).ifEmpty { "error" }
            return
        }
        usage(evt)
        val choice = (evt["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val delta = choice["delta"] as? JsonObject
        delta?.let { applyDelta(it, sink) }
        str(choice["finish_reason"]).takeIf { it.isNotEmpty() }?.let { onFinish(it) }
    }

    private suspend fun applyDelta(delta: JsonObject, sink: WireSink) {
        str(delta["reasoning_content"]).takeIf { it.isNotEmpty() }?.let { r ->
            val idx = thinkingBlock ?: sink.openThinking().also { thinkingBlock = it }
            thinkingBuf.append(r)
            sink.thinkingDelta(idx, r)
        }
        str(delta["content"]).takeIf { it.isNotEmpty() }?.let { c ->
            val idx = textBlock ?: sink.openText().also { textBlock = it }
            emittedText = true
            textBuf.append(c)
            sink.textDelta(idx, c)
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { tc -> applyToolCall(tc as? JsonObject ?: return@forEach, sink) }
    }

    private suspend fun applyToolCall(tc: JsonObject, sink: WireSink) {
        val index = (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val fn = tc["function"] as? JsonObject
        val idx = toolBlocks[index] ?: run {
            val id = str(tc["id"]).ifEmpty { "toolu_$index" }
            val opened = sink.openTool(id, str(fn?.get("name")))
            toolBlocks[index] = opened
            hasToolUse = true
            opened
        }
        str(fn?.get("arguments")).takeIf { it.isNotEmpty() }?.let { sink.inputJsonDelta(idx, it) }
    }

    private fun onFinish(reason: String) {
        finished = true
        when (reason) {
            "tool_calls" -> hasToolUse = true
            "length" -> incomplete = true
            else -> Unit // stop / others -> end_turn
        }
    }

    private fun usage(evt: JsonObject) {
        val u = evt["usage"] as? JsonObject ?: return
        (u["prompt_tokens"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { inputTokens = it }
        (u["completion_tokens"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { outputTokens = it }
    }

    private fun str(el: JsonElement?): String = (el as? JsonPrimitive)?.content ?: ""
}
