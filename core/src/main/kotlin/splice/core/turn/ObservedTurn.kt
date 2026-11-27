package splice.core.turn

import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicInteger

/** Per-turn sequence numbers and delivery isolation. The port itself receives only value types. */
public class ObservedTurn(
    private val observer: TurnObservation,
    private val clock: WallClock,
    private val offers: TurnObserverOffer,
) {
    public val wantsFrames: Boolean get() = observer.wantsFrames
    public val wantsRequests: Boolean get() = observer.wantsRequests
    private val round = AtomicInteger()

    // ClientChannel already serializes every frame at its tap.
    private var seq = 0L

    public fun requestSent(model: String) {
        offers.deliver {
            observer.requestSent(ObservedRequest(round.incrementAndGet(), model, clock()))
        }
    }

    public fun responseReceived(status: Int, attempt: Long, atEpochMs: Long) {
        offers.deliver { observer.responseReceived(ObservedResponse(status, attempt, atEpochMs)) }
    }

    public fun frame(type: String, bytes: Long) {
        offers.deliver { observer.frame(ObservedStreamFrame(++seq, type, bytes, clock())) }
    }

    public fun toolCall(id: String, name: String) {
        offers.deliver { observer.toolCall(ObservedToolCall(id, name, clock())) }
    }

    public fun completed(outcome: String, usage: ObservedUsage, durationMs: Long, stopReason: String?) {
        offers.deliver { observer.completed(ObservedTurnEnd(outcome, usage, durationMs, clock(), stopReason)) }
    }
}
