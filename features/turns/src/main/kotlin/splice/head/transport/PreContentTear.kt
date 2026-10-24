// NEW: V4-242 (2026-09-26) — which websocket tears are re-served over SSE, and in whose words.
//
// A round the upstream tore before the client saw anything of it is re-served over SSE, the way a
// failure terminal before any client frame already is (WsRoundDrive), and by the rule SSE's own
// pre-frame tear follows (TearAwareEvents.reissuable): an I/O failure, before any client frame, that
// the watchdog did not cause. Read as a truncated round instead, a tear was re-anchored on the same
// transport five times: every socket of the Codex outage of 2026-09-25 closed 1011 before output, and
// the HTTP answer that could have named the cause was never asked. Its own unit, so the policy and its
// one import stay out of WsRoundDrive's bill (ConcentrationLawTest, 2026-09-26).
package splice.head.transport

import splice.upstream.failure.TearWords
import java.io.IOException

internal object PreContentTear {

    /** [torn]'s words (TearWords) when the round it tore is re-served over SSE; null when the tear stays
     *  with the translator, which folds it into its honest terminal as before. */
    fun words(torn: Throwable, inputs: WsRoundInputs): String? =
        if (tornBeforeContent(torn, inputs)) TearWords.of(torn) ?: torn::class.simpleName.orEmpty() else null

    /** A tear of a round the client has seen nothing of, which neither the watchdog, a departed client
     *  nor the turn's own cancellation caused. The last two are this path's own: the client's
     *  message_start is written inside the round's flow (WsRoundDriver's ensureStarted), so a dead
     *  client's IOException arrives as a tear too, and ClientChannel sets clientGone before it throws;
     *  and a cancelled turn aborts its round (WsRoundDriver's round job), which reads as a tear the
     *  upstream never made. */
    private fun tornBeforeContent(torn: Throwable, inputs: WsRoundInputs): Boolean =
        torn is IOException &&
            !inputs.frameEmittedThisRound() &&
            inputs.drive.watchdog.fired == null &&
            !inputs.drive.channel.clientGone.get() &&
            inputs.turnJob.isActive
}
