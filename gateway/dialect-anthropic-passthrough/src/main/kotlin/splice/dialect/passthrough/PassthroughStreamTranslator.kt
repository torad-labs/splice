// NEW: (no Node source) upstream Anthropic Messages SSE -> shared WireSink. Kimi's /coding surface
// already speaks the Anthropic event grammar, so this is a near-passthrough that only re-indexes
// blocks onto the sink and enforces two subtle contracts:
//   1. SIGNATURE SYNTHESIS EXACTLY-ONCE: Claude Code silently discards a response whose thinking
//      blocks never receive a signature_delta; Kimi never sends one. We forward an upstream
//      signature if it arrives, else synthesize ONE synthetic signature at block close — never both.
//   2. USAGE NORMALIZATION: Anthropic usage is already disjoint (input excludes cache), but
//      HeadServer's generic payload builder subtracts cachedTokens from inputTokens (OpenAI
//      inclusive convention). So we pre-add the cache buckets back into inputTokens and report
//      cachedTokens = cache_read, making the downstream subtraction reproduce the disjoint numbers.
// L3 honesty is identical to the chat translator: a truncated/failed stream is a retryable Failure,
// never a clean success; ClientAbandoned when the client vanished before any finish. This translator
// only READS the upstream terminal discriminators to drive the WireSink (which has no terminal
// verbs) — it is not a second wire emitter, hence the localized L3 wall exceptions below.
package splice.dialect.passthrough

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

public data class PassthroughTurnContext(
    val clientGone: () -> Boolean,
    val watchdogFired: () -> WatchdogFired?,
    val idleCapMs: Long,
    val totalCapMs: Long,
)

public class PassthroughStreamTranslator(private val ctx: PassthroughTurnContext) : StreamTranslator {

    private enum class Kind { TEXT, THINKING, TOOL, IGNORED }

    private data class Block(val kind: Kind, val wire: WireBlockIndex?) {
        var signatureSeen: Boolean = false
    }

    private val blocks = HashMap<Int, Block>()
    private var hasToolUse = false
    private var emittedText = false
    private var incomplete = false
    private var finished = false
    private val textBuf = StringBuilder()
    private val thinkingBuf = StringBuilder()
    private var inputTokens = 0L
    private var cacheRead = 0L
    private var cacheCreation = 0L
    private var outputTokens = 0L
    private var failureType: ErrorType? = null
    private var failureMessage: String = ""

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
        failureType?.let { TurnOutcome.Failure(it, "kimi: $failureMessage") }
            ?: ctx.watchdogFired()?.let {
                TurnOutcome.Failure(ErrorType.OVERLOADED, "kimi: upstream stalled — aborted; retry")
            }
            ?: if (!finished) unfinishedOutcome() else successOutcome()

    private fun unfinishedOutcome(): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned
        } else {
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                "kimi: stream ended without a terminal event (truncated); retry",
            )
        }

    private fun successOutcome(): TurnOutcome = TurnOutcome.Success(
        hasToolUse = hasToolUse,
        incomplete = incomplete,
        // Anthropic usage is disjoint; re-add the cache buckets so HeadServer's cached-subtraction
        // reproduces the correct disjoint numbers. cachedTokens carries the prompt-cache-read hit.
        usage = Usage(
            inputTokens = inputTokens + cacheRead + cacheCreation,
            outputTokens = outputTokens,
            cachedTokens = cacheRead,
        ),
        thinkingText = thinkingBuf.toString(),
        bodyText = textBuf.toString(),
        emittedText = emittedText,
    )

    private suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        when (str(evt["type"])) {
            "message_start" -> harvestUsage((evt["message"] as? JsonObject)?.get("usage") as? JsonObject)
            "content_block_start" -> onBlockStart(evt, sink)
            "content_block_delta" -> onBlockDelta(evt, sink)
            "content_block_stop" -> onBlockStop(evt, sink)
            // ast-grep-ignore: kt-l3-sole-wire-terminals — reading upstream discriminator, not emitting
            "message_delta" -> onMessageDelta(evt)
            // ast-grep-ignore: kt-l3-sole-wire-terminals — reading upstream discriminator, not emitting
            "message_stop" -> finished = true
            "error" -> onError(evt)
            else -> Unit // ping / unknown events are ignored
        }
    }

    private suspend fun onBlockStart(evt: JsonObject, sink: WireSink) {
        val index = intIndex(evt) ?: return
        val cb = evt["content_block"] as? JsonObject
        blocks[index] = when (str(cb?.get("type"))) {
            "text" -> Block(Kind.TEXT, sink.openText())
            "thinking" -> Block(Kind.THINKING, sink.openThinking())
            "tool_use" -> {
                // Pass Kimi's tool id VERBATIM: it round-trips back to Kimi on the next turn.
                hasToolUse = true
                Block(Kind.TOOL, sink.openTool(str(cb?.get("id")), str(cb?.get("name"))))
            }
            // server_tool_use / web_search_tool_result / unknown: record + swallow its deltas.
            else -> Block(Kind.IGNORED, null)
        }
    }

    // The upstream delta type already matches the (non-ignored) block it targets, so we dispatch on
    // the delta type; the open block's wire is the only thing we need. Ignored blocks have no wire.
    private suspend fun onBlockDelta(evt: JsonObject, sink: WireSink) {
        val block = blocks[intIndex(evt) ?: return] ?: return
        val wire = block.wire ?: return // ignored block: swallow
        applyDelta(block, wire, evt["delta"] as? JsonObject ?: EMPTY, sink)
    }

    private suspend fun applyDelta(block: Block, wire: WireBlockIndex, delta: JsonObject, sink: WireSink) {
        when (str(delta["type"])) {
            "text_delta" -> {
                val t = str(delta["text"])
                textBuf.append(t)
                emittedText = true
                sink.textDelta(wire, t)
            }
            "thinking_delta" -> {
                val t = str(delta["thinking"])
                thinkingBuf.append(t)
                sink.thinkingDelta(wire, t)
            }
            "input_json_delta" -> sink.inputJsonDelta(wire, str(delta["partial_json"]))
            "signature_delta" -> {
                sink.signatureDelta(wire, str(delta["signature"]))
                block.signatureSeen = true
            }
            else -> Unit
        }
    }

    private suspend fun onBlockStop(evt: JsonObject, sink: WireSink) {
        val block = blocks[intIndex(evt) ?: return] ?: return
        val wire = block.wire ?: return // ignored block: nothing was opened
        if (block.kind == Kind.THINKING && !block.signatureSeen) {
            // Synthesize EXACTLY ONE signature so Claude Code keeps the thinking block.
            sink.signatureDelta(wire, SYNTHETIC_SIGNATURE)
            block.signatureSeen = true
        }
        sink.closeBlock(wire)
    }

    private fun onMessageDelta(evt: JsonObject) {
        val reason = str((evt["delta"] as? JsonObject)?.get("stop_reason"))
        when (reason) {
            "tool_use" -> hasToolUse = true
            "max_tokens" -> incomplete = true
            else -> Unit // stop_sequence / end_turn / other -> end_turn semantics
        }
        harvestUsage(evt["usage"] as? JsonObject)
    }

    private fun onError(evt: JsonObject) {
        val err = evt["error"] as? JsonObject
        failureType = when (str(err?.get("type"))) {
            "overloaded_error" -> ErrorType.OVERLOADED
            "rate_limit_error" -> ErrorType.RATE_LIMIT
            "authentication_error" -> ErrorType.AUTHENTICATION
            "invalid_request_error" -> ErrorType.INVALID_REQUEST
            else -> ErrorType.API_ERROR
        }
        failureMessage = str(err?.get("message")).ifEmpty { "error" }
    }

    private fun harvestUsage(u: JsonObject?) {
        u ?: return
        num(u, "input_tokens")?.let { inputTokens = it }
        num(u, "cache_read_input_tokens")?.let { cacheRead = it }
        num(u, "cache_creation_input_tokens")?.let { cacheCreation = it }
        num(u, "output_tokens")?.let { outputTokens = it }
    }

    private fun intIndex(evt: JsonObject): Int? = (evt["index"] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun num(obj: JsonObject, key: String): Long? = (obj[key] as? JsonPrimitive)?.content?.toLongOrNull()

    // JsonNull IS a JsonPrimitive whose `.content` is "null"; treat an explicit null as absent so it
    // never leaks into text/ids and a null field cannot masquerade as a real value (L3).
    private fun str(element: JsonElement?): String =
        (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: ""

    private companion object {
        // Short stable constant — Kimi never verifies signatures; Claude Code only needs one present.
        const val SYNTHETIC_SIGNATURE = "splice-synth-v1"
        val EMPTY = JsonObject(emptyMap())
    }
}
