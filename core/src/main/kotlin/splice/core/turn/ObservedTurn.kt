package splice.core.turn

import splice.core.util.WallClock
import java.util.concurrent.atomic.AtomicInteger

/** Per-turn sequence numbers and delivery isolation. The port itself receives only value types. */
public class ObservedTurn(private val observer: TurnObservation, private val clock: WallClock) {
    public val wantsFrames: Boolean get() = observer.wantsFrames
    public val wantsRequests: Boolean get() = observer.wantsRequests
    private val round = AtomicInteger()

    // ClientChannel already serializes every frame at its tap.
    private var seq = 0L

    public fun requestSent(model: String, bytes: Long) {
        TurnObserverOffer.deliver {
            observer.requestSent(ObservedRequest(round.incrementAndGet(), model, bytes, clock()))
        }
    }

    public fun responseReceived(status: Int, attempt: Long, atEpochMs: Long) {
        TurnObserverOffer.deliver { observer.responseReceived(ObservedResponse(status, attempt, atEpochMs)) }
    }

    public fun frame(type: String, bytes: Long) {
        TurnObserverOffer.deliver { observer.frame(ObservedStreamFrame(++seq, type, bytes, clock())) }
    }

    public fun toolCall(id: String, name: String) {
        TurnObserverOffer.deliver { observer.toolCall(ObservedToolCall(id, name, clock())) }
    }

    public fun completed(outcome: String, usage: ObservedUsage, durationMs: Long) {
        TurnObserverOffer.deliver { observer.completed(ObservedTurnEnd(outcome, usage, durationMs, clock())) }
    }
}
