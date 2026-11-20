// NEW: V4-165 (2026-09-19) — the provider's turn, shaped for the wire, split out of
// TurnPreparation (concentration: V4-165's lease guard pushed that file into the HIGH band). The
// compaction tail, the compaction request hash and the system/slot prompt layers moved verbatim;
// what is new is the guard: a provider's turn can now hold something (BuiltTurn.onEnd, a llama-server
// slot lease), and a build that throws between the provider and the drive must give it back.
// A door mode on every dialect (docs/specs/harness.md, decision 12, Modes): the harness edits a request in
// Anthropic's Messages shape, and on a head whose provider sends another dialect the edited request is built again
// here the way the turn's own was, so the dialect's own translator shapes what splice added.
package splice.head.turn

import kotlinx.serialization.json.JsonObject
import splice.core.compaction.EffectiveCompactionInstructions
import splice.core.parse.AnthropicTurnBody
import splice.core.parse.lenientJson
import splice.core.perf.TurnPerf
import splice.core.turn.TurnCompaction
import splice.core.wire.AnthropicRequest
import splice.head.HeadDeps
import splice.head.compaction.CompactionReplay
import splice.head.port.HarnessRequest
import splice.head.turn.review.AutoModeReview
import splice.head.turn.review.SideRequest
import splice.upstream.BuiltTurn
import splice.upstream.Provider

internal class ProviderTurnBuild(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val replay: CompactionReplay,
) {
    private val prompts = TurnPrompts(provider, deps)
    private val review = AutoModeReview()
    private val side = SideRequest()

    /** [client] is the client's user agent, which says whether the request is Claude Code's. */
    fun build(
        parsed: AnthropicTurnBody,
        compact: Boolean,
        sessionId: String?,
        perf: TurnPerf,
        client: String?,
    ): BuiltTurn {
        val effective = deps.seams.session.compactionTail.resolve(
            compact,
            provider.catalog.stripSuffixes(parsed.typed.model),
            sessionId,
        )
        // Claude Code's auto-mode review and its side requests go upstream with what Claude Code wrote and nothing of
        // the head's.
        val aside = review.recognized(parsed.typed, perf) || side.recognized(parsed.typed, client, perf)
        val base = provider.buildTurn(parsed, compact, sessionId)
        return endingOnFailure(base) {
            val withTail = tailed(base, effective, compact)
            // AFTER the tail, so the compaction request hash and its applied check keep reading the
            // provider body BEFORE any tail — a retry must still match its recording byte for byte.
            if (aside) {
                withTail
            } else {
                prompts.applySlotPrompt(prompts.applySystemPrompt(withTail, sessionId, perf), sessionId, perf)
            }
        }
    }

    /** What [turn] holds (BuiltTurn.onEnd) ends here unless [block] hands the turn on — a build
     *  that throws between the provider and the drive must not keep a slot leased forever. */
    inline fun <T> endingOnFailure(turn: BuiltTurn, block: () -> T): T {
        var handedOn = false
        try {
            return block().also { handedOn = true }
        } finally {
            if (!handedOn) turn.onEnd?.ended()
        }
    }

    /** [base] with the compaction tail placed, and the compaction meta that says whether it was. */
    private fun tailed(base: BuiltTurn, effective: EffectiveCompactionInstructions?, compact: Boolean): BuiltTurn {
        val tailed = effective?.tailText?.let { provider.withCompactionTail(base, it) } ?: base
        // A dialect that cannot place the tail (no user text to extend) returns the request as it
        // was: the meta then says so instead of claiming instructions the wire never carried.
        val applied = effective?.tailText == null || tailed.requestBody != base.requestBody
        // V4-166: without the routing fields (id_slot), so a retry routed to another slot still matches.
        val hash = if (compact) {
            replay.bodyHash(JsonObject(base.requestBody - base.routingFields))
        } else {
            null
        }
        return effective?.let { eff ->
            tailed.copy(
                meta = tailed.meta.copy(
                    compaction = TurnCompaction(
                        instructions = if (applied) eff.text else null,
                        source = if (applied) eff.source else "${eff.source} (not applied)",
                        requestHash = hash,
                    ),
                ),
            )
        } ?: tailed.copy(meta = tailed.meta.copy(compaction = tailed.meta.compaction.copy(requestHash = hash)))
    }
}

/** What the harness reads of one request, and how a request it edits is built for the turn's provider: null where the
 *  provider sends Messages. */
internal data class HarnessAsked(val request: HarnessRequest, val rebuild: TurnRebuild? = null)

/** The request the provider builds for a Messages request of the turn [parsed] began, built as [build] built the
 *  turn's own: with [compact], for [sessionId], counted on [perf], for the client [client] names. */
internal class TurnRebuild(
    private val build: ProviderTurnBuild,
    private val parsed: AnthropicTurnBody,
    private val compact: Boolean,
    private val sessionId: String?,
    private val perf: TurnPerf,
    private val client: String?,
) {
    /** The client's request, in the Messages shape it arrived in. */
    val asked: JsonObject get() = parsed.raw

    /** The provider's request for [body], with what routes [first] (its routing fields) kept as [first] has it. A
     *  build that leased something gives it back at once: the turn already holds what [first] leased. */
    fun built(body: JsonObject, first: BuiltTurn): BuiltTurn {
        val typed = lenientJson.decodeFromJsonElement(AnthropicRequest.serializer(), body)
        val again = build.build(AnthropicTurnBody(body, typed, parsed.agentId), compact, sessionId, perf, client)
        again.onEnd?.ended()
        val routed = first.routingFields.mapNotNull { field -> first.requestBody[field]?.let { field to it } }
        return first.sending(JsonObject(again.requestBody + routed))
    }
}
