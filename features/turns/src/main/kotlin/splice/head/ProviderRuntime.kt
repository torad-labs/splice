// NEW: gateway-no-heads. A provider served by the gateway directly, with no head: the same turn-serving assembly a head
// holds (TurnService), with nothing of a head's own in front of it, no bound port and no legacy door. The gateway
// admitted the caller at its own door before it calls here, so a gateway turn carries its body, door and window
// ([GatewayTurn]) and never its own credential upstream (GatewayUpstreamHeaders): the provider runs it on the
// credential it holds, or answers that it holds none.
package splice.head

import io.ktor.server.application.ApplicationCall
import splice.core.failure.VoiceFacts
import splice.core.topology.ForwardHeaders
import splice.core.usage.QuotaFull
import splice.upstream.Provider
import java.util.concurrent.atomic.AtomicInteger

public class ProviderRuntime(
    provider: Provider,
    assembled: HeadDeps,
    voiceFacts: VoiceFacts = VoiceFacts(),
    forwarded: ForwardHeaders = ForwardHeaders(emptyList()),
) {
    /** [assembled], with no other head to hand a turn to: a gateway turn is this provider's and nothing relays it to a
     *  head server (door-default's hand-off to another head is a head's, so it is closed here whatever the caller
     *  assembled). */
    private val deps: HeadDeps = assembled.copy(traffic = assembled.traffic.copy(others = OtherHeads { null }))

    private val turns = TurnService(
        provider,
        deps,
        voiceFacts,
        GatewayUpstreamHeaders(CallerHeaders(deps, forwarded), deps.policy.forwardClientAuth),
    )

    /** Calls still inside [serve] or [countTokens]: the drain waits for them as a head's engine does for its own. */
    private val calls = AtomicInteger()

    public val key: String get() = turns.provider.key

    /** The gateway's own [turn], with no door of this provider's own: [call] is served straight into admission, the
     *  body, door and window being [turn]'s and every header the call's. */
    public suspend fun serve(call: ApplicationCall, turn: GatewayTurn) {
        counted {
            GatewayTurns.attach(call, turn)
            turns.admission.handleMessages(call, turns.admission.arrivalTime())
        }
    }

    /** A count of [turn]'s body, scaled to the window [turn] names. */
    public suspend fun countTokens(call: ApplicationCall, turn: GatewayTurn) {
        counted {
            GatewayTurns.attach(call, turn)
            turns.countTokens.handleCountTokens(call)
        }
    }

    public fun modelsJson(): String = turns.diagnostics.modelsJson()

    /** The plan window the provider reads fully used now, if one is: a reading, never a refusal (V4-452). */
    public fun quotaFull(): QuotaFull? = deps.turnQuota.full()

    /** No listener to bind: starting is the driver's fresh health baseline and the admission window opening. */
    public fun start() {
        turns.driver.resetHealth()
        turns.driver.headStarted()
        turns.window.open()
    }

    /** Refuse new turns, let running ones finish within the stop-drain budget and cut what is left with the restart
     *  line (the drain a head runs, TurnService.settle), then settle the provider's books. */
    public suspend fun drain() {
        turns.window.close()
        turns.settle { calls.get() }
        turns.close(retired = false)
    }

    private suspend inline fun counted(block: () -> Unit) {
        calls.incrementAndGet()
        try {
            block()
        } finally {
            calls.decrementAndGet()
        }
    }
}
