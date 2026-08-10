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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.firstLong
import splice.core.util.strOrEmpty
import splice.spi.BufferCapacity
import splice.spi.StreamTranslator
import splice.spi.TerminalStates
import splice.spi.WatchdogFired
import splice.spi.WireSink
import splice.spi.terminalPrecedence
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
    private var emittedThinking = false
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

    // NF-06: latched when BufferCapacity trips; never provider-reported (the verdict is local).
    private var runawayGuard: String? = null

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        try {
            upstream.collect { evt ->
                // NF-06: the shared runaway valve Chat already had.
                if (BufferCapacity.over(textBuf.length, thinkingBuf.length)) {
                    runawayGuard = RUNAWAY_GUARD_MESSAGE
                    return@collect
                }
                onEvent(evt, sink)
            }
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

    // Ordering enforced by the shared spi.terminalPrecedence (a FINISHED turn beats a late
    // watchdog fire — preferring watchdog here discarded successful kimi turns and burned quota).
    private fun terminalOutcome(): TurnOutcome = terminalPrecedence(
        TerminalStates(
            // NF-06: a tripped runaway valve outranks the provider slot — the buffers were truncated.
            providerFailure = runawayGuard?.let {
                TurnOutcome.Failure(ErrorType.API_ERROR, it, providerReported = false)
            } ?: failureType?.let {
                // a signal the BACKEND sent — an upstream SSE error event (e.g. overloaded_error) or
                // a non-clean stop_reason (CX-07, see [stopReasonFailure]) — provider-reported (G20)
                TurnOutcome.Failure(it, "kimi: $failureMessage", providerReported = true)
            },
            finished = finished,
            watchdogFired = ctx.watchdogFired(),
        ),
        onFinished = ::successOutcome,
        onWatchdog = { TurnOutcome.Failure(ErrorType.OVERLOADED, "kimi: upstream stalled — aborted; retry") },
        onUnfinished = ::unfinishedOutcome,
    )

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
        emittedThinking = emittedThinking,
    )

    private suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        when (strOrEmpty(evt["type"])) {
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
        blocks[index] = when (strOrEmpty(cb?.get("type"))) {
            "text" -> Block(Kind.TEXT, sink.openText())
            // CX-09: this block IS content the client receives — record it so the empty-turn
            // honesty gate never calls a thinking-only turn empty (passthrough pins
            // showReasoning=THINKING precisely BECAUSE reasoning rides natively here).
            "thinking" -> Block(Kind.THINKING, sink.openThinking()).also { emittedThinking = true }
            "tool_use" -> {
                // Pass Kimi's tool id VERBATIM: it round-trips back to Kimi on the next turn — a
                // JsonNull id must never leak as the literal string "null" into that round-trip (L3);
                // strOrEmpty keeps it filtered (review 2026-07-22 round 3).
                hasToolUse = true
                Block(Kind.TOOL, sink.openTool(strOrEmpty(cb?.get("id")), strOrEmpty(cb?.get("name"))))
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
        when (strOrEmpty(delta["type"])) {
            "text_delta" -> {
                val t = strOrEmpty(delta["text"])
                textBuf.append(t)
                emittedText = true
                sink.textDelta(wire, t)
            }
            "thinking_delta" -> {
                val t = strOrEmpty(delta["thinking"])
                thinkingBuf.append(t)
                sink.thinkingDelta(wire, t)
            }
            "input_json_delta" -> sink.inputJsonDelta(wire, strOrEmpty(delta["partial_json"]))
            "signature_delta" -> {
                sink.signatureDelta(wire, strOrEmpty(delta["signature"]))
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
        val reason = strOrEmpty((evt["delta"] as? JsonObject)?.get("stop_reason"))
        when (reason) {
            "tool_use" -> hasToolUse = true
            "max_tokens" -> incomplete = true
            // CX-07 (L3): every OTHER value used to land in a bare `else -> Unit` with end_turn
            // semantics, so a generation the BACKEND refused, paused or hard-truncated reached the
            // client as a clean, complete Success. The three non-clean Anthropic terminals now latch
            // the provider slot; end_turn / stop_sequence / an absent stop_reason / an unrecognized
            // vendor value keep end_turn semantics (see [stopReasonFailure]). First latch wins so a
            // later genuine `error` event can never be overwritten by a trailing message_delta.
            else -> stopReasonFailure(reason)?.let { (type, why) ->
                if (failureType == null) {
                    failureType = type
                    failureMessage = why
                }
            }
        }
        harvestUsage(evt["usage"] as? JsonObject)
    }

    private fun onError(evt: JsonObject) {
        val err = evt["error"] as? JsonObject
        failureType = when (strOrEmpty(err?.get("type"))) {
            "overloaded_error" -> ErrorType.OVERLOADED
            "rate_limit_error" -> ErrorType.RATE_LIMIT
            "authentication_error" -> ErrorType.AUTHENTICATION
            "invalid_request_error" -> ErrorType.INVALID_REQUEST
            else -> ErrorType.API_ERROR
        }
        failureMessage = strOrEmpty(err?.get("message")).ifEmpty { "error" }
    }

    private fun harvestUsage(u: JsonObject?) {
        u ?: return
        u.firstLong("input_tokens")?.let { inputTokens = it }
        u.firstLong("cache_read_input_tokens")?.let { cacheRead = it }
        cacheCreationTokens(u)?.let { cacheCreation = it }
        u.firstLong("output_tokens")?.let { outputTokens = it }
    }

    /** CX-18: the flat total, else the sum of Anthropic's newer per-TTL `cache_creation` buckets.
     *  Flat wins so a backend sending both is not double-counted, and the sum (not a first-of read)
     *  is what the two TTL buckets mean. These tokens fold into inputTokens in [successOutcome],
     *  so missing them understated the whole context-window percentage on cache-writing turns. */
    private fun cacheCreationTokens(u: JsonObject): Long? =
        u.firstLong("cache_creation_input_tokens")
            ?: (u["cache_creation"] as? JsonObject)?.let { nested ->
                // SCOPED to *_input_tokens, not every value in the object. Summing everything
                // picks up a future non-additive sibling — a `total`, a `ttl` in seconds — and
                // folds it into inputTokens and therefore used_percentage, i.e. premature
                // auto-compaction: the same class CX-18 exists to prevent, in the other
                // direction. Naming the two known TTL keys instead would miss a new
                // ephemeral_1d_input_tokens bucket, so the suffix is the right seam.
                val parts = nested.filterKeys { it.endsWith("_input_tokens") }
                    .values.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() }
                parts.sum().takeIf { parts.isNotEmpty() }
            }

    private fun intIndex(evt: JsonObject): Int? = (evt["index"] as? JsonPrimitive)?.content?.toIntOrNull()

    private companion object {
        // Short stable constant — Kimi never verifies signatures; Claude Code only needs one present.
        const val SYNTHETIC_SIGNATURE = "splice-synth-v1"
        val EMPTY = JsonObject(emptyMap())
    }
}

// NF-06 runaway-upstream guard message; the cap lives in spi.BufferCapacity (one source, three dialects).
private const val RUNAWAY_GUARD_MESSAGE = "kimi: response exceeded max buffered size — aborting"

private const val CONTEXT_EXCEEDED_MESSAGE =
    "generation stopped: the model context window was exceeded (stop_reason=model_context_window_exceeded)"

/**
 * CX-07 (L3): the Anthropic `stop_reason` values that are NOT a clean completion, mapped to the
 * honest failure each one is. The protocol ships SEVEN stop reasons; four are clean — `end_turn`,
 * `stop_sequence`, `tool_use`, `max_tokens` — and these three are not:
 *   · `refusal` — the model refused to generate; a censored turn, same class as Chat's content_filter;
 *   · `pause_turn` — the backend paused a long-running turn; retryable, not a finished answer;
 *   · `model_context_window_exceeded` — a HARD truncation distinct from `max_tokens`, so it must not
 *     fold into `incomplete` (that would report the ordinary "ran out of room" stop for a turn the
 *     backend could not run at all).
 * Returns null for a clean value, for an absent stop_reason (`""`), and for an unrecognized one.
 *
 * WHY THE REMAINDER STAYS OPEN-SAFE instead of failing everything outside the clean four (decision
 * 2026-08-09; the open-remainder alternative was considered and rejected): this dialect serves
 * exactly one backend, Kimi's `/coding` surface, and OpenAI-compatible vendors demonstrably invent
 * enumeration values — `ChatStreamTranslator.onFinish` already carries a `max_tokens` arm for
 * vendors that ignore the spec's `length`. An open remainder would turn one off-spec value on a
 * WORKING turn into a failed turn, and a false Failure on working traffic is worse than the silent
 * success it replaces. The cost is that stop_reason #8 arrives invisible; making unknown
 * discriminator values COUNTED needs a per-turn telemetry channel the dialects do not have (see the
 * ledger note on W4-A) and is proposed as its own item rather than smuggled in here.
 *
 * Top-level — off the class function budget; reads only its argument.
 */
private fun stopReasonFailure(reason: String): Pair<ErrorType, String>? = when (reason) {
    "refusal" -> ErrorType.API_ERROR to "generation refused by the model (stop_reason=refusal)"
    "pause_turn" -> ErrorType.OVERLOADED to "backend paused the turn (stop_reason=pause_turn) — retry"
    "model_context_window_exceeded" -> ErrorType.API_ERROR to CONTEXT_EXCEEDED_MESSAGE
    else -> null
}
