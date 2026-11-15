// NEW: server mode (docs/specs/server-mode.md, F4: "a refused connection fails over at once"). A vendor that is
// several servers (VendorRoute.unreachable): a server that took no connection got no byte of the request, so sending
// it to another can double nothing. The route moves the turn and the same request goes out again at once, on none of
// the waits the engine gives a path that did not answer; with no server left the turn ends now with the error it met,
// where one server's refusal would be waited out. A request a server may already hold is never moved: that failure,
// like every failure of a vendor with one place, is the engine's to plan (RetrySteps.thrown).
package splice.upstream.transport

import splice.core.util.ERR_SNIPPET
import splice.upstream.Reroute
import kotlin.coroutines.cancellation.CancellationException

internal class RouteMoves(private val transportFailures: TransportFailures) {
    /** The step after [e] when the vendor's route moved the turn to another server, which is to send again now; null
     *  when the failure is the engine's to plan. Throws [e] when the route has no server left to move to. */
    fun elsewhere(e: Throwable, ctx: PostContext, state: RetryState, streamHandedOff: Boolean): LoopStep<Nothing>? {
        if (streamHandedOff || e is CancellationException) return null
        val route = ctx.recovery.route?.takeIf { transportFailures.neverConnected(e) } ?: return null
        // Read while the route is still on the server that refused: the note names the one the turn left.
        val left = TransportFaultLine.of(e, ctx.target).take(ERR_SNIPPET)
        return when (route.unreachable()) {
            Reroute.STAY -> null
            Reroute.NOWHERE -> throw e
            Reroute.MOVED -> {
                ctx.onRetry("transport $left; the turn goes to another of the provider's upstreams at once")
                state.lastThrown = e
                state.lastTear = null
                state.pendingRetry = RetryKind.ORDINARY
                LoopStep.Continue
            }
        }
    }
}
