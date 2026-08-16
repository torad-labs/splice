// NEW: (no Node source) upstream Anthropic Messages SSE -> shared WireSink. Kimi's /coding surface
// already speaks the Anthropic event grammar, so this is a near-passthrough that only re-indexes
// blocks onto the sink and enforces two subtle contracts:
//   1. SIGNATURE SYNTHESIS EXACTLY-ONCE, and only when the head asks for it
//      (PassthroughQuirks.synthesizeSignatures): Claude Code silently discards a response whose
//      thinking blocks never receive a signature_delta, and Kimi never sends one. We forward an
//      upstream signature if it arrives, else synthesize ONE at block close — never both. An
//      upstream that SIGNS and VERIFIES leaves this off: a block truncated before its signature
//      would otherwise persist a forged signature into the transcript and hand it back next turn.
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
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.DaemonLog
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
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails (wall kt-no-println). The translator's only anomaly channel — it has no
     *  per-turn perf handle (Provider.streamTranslator threads none). Uninstalled, DaemonLog is a
     *  no-op — never a silent stderr write — so tests need not thread it; once Main installs the
     *  process sink this same reference starts writing to it, so no call site (including
     *  PassthroughProvider) needs to pass it explicitly. */
    val log: (String) -> Unit = DaemonLog::write,
)

public class PassthroughStreamTranslator(
    private val ctx: PassthroughTurnContext,
    private val quirks: PassthroughQuirks,
) : StreamTranslator {

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

    // PT-001: latched after the first unmapped-index delta is logged (TurnDriver.malformedLogged's
    // idiom) — a chatty misbehaving upstream can emit many post-stop deltas in a row, and per-delta
    // logging is unbounded, synchronous daemon.log I/O on the hot path. The anomaly stays visible;
    // it just stops repeating.
    private var unmappedIndexLogged = false

    // The two verdict-message rules ride a collaborator rather than this class: the translator holds
    // 14 members against detekt's TooManyFunctions ceiling of 15, so folding both in fails the build.
    private val failureRules = PassthroughFailureRules()

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        try {
            upstream
                // NF-06: the shared runaway valve Chat already had. takeWhile (not a skip inside
                // collect) so the first breach CANCELS collection and Flow unwinds the upstream —
                // otherwise a still-streaming upstream keeps the turn slot and quota live until it
                // chooses to close, with every later event consumed and thrown away.
                .takeWhile {
                    val withinCapacity = !BufferCapacity.over(textBuf.length, thinkingBuf.length)
                    if (!withinCapacity) runawayGuard = failureRules.runawayGuardMessage(quirks.providerTag)
                    withinCapacity
                }
                .collect { evt -> onEvent(evt, sink) }
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
                // a non-clean stop_reason (CX-07, see [PassthroughFailureRules.stopReasonFailure]) —
                // provider-reported (G20)
                TurnOutcome.Failure(it, "${quirks.providerTag}: $failureMessage", providerReported = true)
            },
            finished = finished,
            watchdogFired = ctx.watchdogFired(),
        ),
        onFinished = ::successOutcome,
        onWatchdog = {
            TurnOutcome.Failure(ErrorType.OVERLOADED, "${quirks.providerTag}: upstream stalled — aborted; retry")
        },
        onUnfinished = ::unfinishedOutcome,
    )

    private fun unfinishedOutcome(): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned
        } else {
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                "${quirks.providerTag}: stream ended without a terminal event (truncated); retry",
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
            "thinking" -> Block(Kind.THINKING, sink.openThinking())
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
        val index = intIndex(evt) ?: return
        val block = blocks[index]
        if (block == null) {
            // PT-001: an index with no live block entry (never opened, or already closed) drops
            // its content — never silently: this is the translator's only anomaly channel. Logged
            // ONCE per turn, not once per delta (a torn/misbehaving upstream can emit many).
            if (!unmappedIndexLogged) {
                ctx.log("[${quirks.providerTag}] content_block_delta for unmapped index=$index — dropped\n")
                unmappedIndexLogged = true
            }
            return
        }
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
                // CX-09: the flag means "the client RECEIVED reasoning", not "a block was opened".
                // Kimi can open a thinking block and close it having sent nothing; counting that
                // as content short-circuits the empty-turn honesty gate and lets a turn carrying
                // literally zero characters end as a clean terminal — the L3 violation CX-09
                // exists to close. Set it where chat and responses set theirs: on real content.
                if (t.isNotBlank()) emittedThinking = true
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
        // PT-006: remove on close — a delta arriving after this index's content_block_stop must
        // find no entry (and drop honestly via onBlockDelta's unmapped-index path), not apply
        // itself to a logically closed block.
        val block = blocks.remove(intIndex(evt) ?: return) ?: return
        val wire = block.wire ?: return // ignored block: nothing was opened
        val unsignedThinking = block.kind == Kind.THINKING && !block.signatureSeen
        if (quirks.synthesizeSignatures && unsignedThinking) {
            // Synthesize EXACTLY ONE signature so Claude Code keeps the thinking block. Quirk-gated:
            // an upstream that SIGNS and VERIFIES must never receive this back — a block truncated
            // before its signature would otherwise persist a forged one into the transcript.
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
            // vendor value keep end_turn semantics (see [PassthroughFailureRules.stopReasonFailure]).
            // First latch wins so a later genuine `error` event can never be overwritten by a
            // trailing message_delta.
            else -> failureRules.stopReasonFailure(reason)?.let { (type, why) ->
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
}

// Short stable constant — Kimi never verifies signatures; Claude Code only needs one present.
private const val SYNTHETIC_SIGNATURE = "splice-synth-v1"

// FILE SCOPE ON PURPOSE: one shared empty object, read on the delta hot path — as a member it would
// be rebuilt per translator instance (one per turn).
private val EMPTY = JsonObject(emptyMap())

private const val CONTEXT_EXCEEDED_MESSAGE =
    "generation stopped: the model context window was exceeded (stop_reason=model_context_window_exceeded)"

/**
 * The translator's two verdict-message rules, on their own type because
 * [PassthroughStreamTranslator] holds 14 members against detekt's TooManyFunctions ceiling of 15.
 * Both read only their arguments — no state, no wire access.
 */
private class PassthroughFailureRules {

    /** NF-06 runaway-upstream guard message; the cap lives in spi.BufferCapacity (one source,
     *  three dialects). */
    fun runawayGuardMessage(providerTag: String): String =
        "$providerTag: response exceeded max buffered size — aborting"

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
     */
    fun stopReasonFailure(reason: String): Pair<ErrorType, String>? = when (reason) {
        "refusal" -> ErrorType.API_ERROR to "generation refused by the model (stop_reason=refusal)"
        "pause_turn" -> ErrorType.OVERLOADED to "backend paused the turn (stop_reason=pause_turn) — retry"
        "model_context_window_exceeded" -> ErrorType.API_ERROR to CONTEXT_EXCEEDED_MESSAGE
        else -> null
    }
}
