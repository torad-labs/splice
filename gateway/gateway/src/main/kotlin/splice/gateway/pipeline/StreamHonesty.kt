// NEW: CX-09 empty-turn honesty + the L2 mirror gate. Split from StreamFinish
// (concentration, 2026-08-19) so that file is not billed for the mirror/spi
// subsystems. Same-package.
package splice.gateway.pipeline

import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.gateway.reasoning.Mirror
import splice.spi.WireSink

internal class StreamHonesty(private val mirrorReasoning: Boolean) {
    private val mirror = Mirror()

    // The mirror_reasoning knob gates the CALL — mirrorInto stays the single L2 definition
    // (the ast-grep wall pins it to Mirror.kt); off = no transcript reinjection, display untouched.
    suspend fun mirrorGated(sink: WireSink, thinkingText: String?, meta: TurnMeta) {
        if (mirrorReasoning) mirror.mirrorInto(sink, thinkingText, meta.showReasoning, meta.compact)
    }

    /** CX-09: the empty-turn verdict — nothing reached the client this turn and nothing will.
     *
     *  Both halves matter. [TurnOutcome.Success.emittedThinking] covers what ALREADY went out: the
     *  translators stream native thinking blocks regardless of the mirror knob and regardless of
     *  showReasoning, so a thinking-only turn is not empty just because the text mirror will stay
     *  quiet. [willMirrorHere] covers what still might go out. Only when both are false has the
     *  client genuinely received nothing — which today means the harvest fallback, where the
     *  thinking buffer is refilled from the completed response without the sink ever being
     *  touched. */
    fun nothingReachesTheClient(outcome: TurnOutcome.Success, meta: TurnMeta): Boolean =
        !outcome.emittedThinking && !willMirrorHere(outcome.thinkingText, meta)

    /** CX-09: the same predicate [mirrorGated] obeys, including the locked flag.
     *
     *  This answers only "will the TEXT MIRROR emit?" — it is NOT the whole question. Translators
     *  emit native thinking blocks independently of both the knob and showReasoning, and on
     *  anthropic-passthrough `showReasoning = THINKING` is set PRECISELY BECAUSE reasoning already
     *  rides the wire natively (PassthroughRequestBuilder: "pick the showReasoning value that makes
     *  mirrorInto a no-op"). Reading that sentinel as "nothing will cover this turn" turned every
     *  thinking-only kimi turn into an API_ERROR — a regression caught in adversarial review before
     *  it shipped. So the honesty gate asks [TurnOutcome.Success.emittedThinking] FIRST, and only
     *  falls back to this predicate when nothing reached the wire at all. */
    private fun willMirrorHere(thinkingText: String?, meta: TurnMeta): Boolean =
        mirrorReasoning && mirror.willMirror(thinkingText, meta.showReasoning, meta.compact)
}
