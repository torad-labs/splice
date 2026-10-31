// The IN-BAND TYPE RULE, pinned as a table.
//
// The rule lives in ClientWire (core/failure) since failure-handling slice 1, which folded SseEmitter's
// PreContentWireType into the one place the client's answer is chosen, and this pins every row of it, because the
// interesting content of the rule is not what it remaps but what it REFUSES to: before any content the failures a
// re-send can heal read as the one type the client re-sends, everything a re-send cannot heal reads as the in-band
// class it stops on, and after content every type is left alone.
//
// Failure-handling slice 4 chose those types by family instead of by permanence (final-design.md section 11): on a
// committed 200 with nothing shown, R reads overloaded_error and every permanent ending reads invalid_request_error,
// which the record shows 2.1.310 prints and does not re-send (inband-signed-notice). Only the client's own forwarded
// login keeps authentication_error. Each cell asks with a leaf of that family; ClientWireRecordTest walks every leaf
// and position against the record.
//
// The E2E half of this row lives next door: RetryAlwaysArmedTest's sweep drives a live
// api_error-in-200 path and asserts the body the CLIENT receives, and HeadServerFailureBranchTest
// asserts the same path's message and perf row are unchanged by the relabel.
package splice.head.wire

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.ClientWire
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LoginOwner
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.turn.ErrorType
import java.time.Instant

class TurnKnownEndTest {

    /** The type the client reads for [cause] while nothing has reached it yet, the 200 already committed. */
    private fun beforeContent(cause: FailureCause): ErrorType =
        ClientWire.answer(cause, Position(Moment.COMMITTED, streamBegan = true)).type

    /** The type the client reads for [cause] once content has reached it. */
    private fun afterContent(cause: FailureCause): ErrorType =
        ClientWire.answer(cause, Position(Moment.MID_CONTENT, streamBegan = true)).type

    private val rateLimit = FailureCause.Provider.Limited(Lift.Unstated, window = null)
    private val apiError = FailureCause.Provider.Unrecognized(clientError = false)

    @Test
    fun `a pre-content rate limit or api error is wired as overloaded`() {
        // Claude Code 2.1.257 retries an IN-BAND error only when the body carries overloaded_error
        // (a 429/529 is retried by STATUS), so these two would otherwise end the session where a
        // retry heals it. Before content, nothing the client has read is at stake.
        assertEquals(ErrorType.OVERLOADED, beforeContent(rateLimit))
        assertEquals(ErrorType.OVERLOADED, beforeContent(apiError))
    }

    @Test
    fun `after content the type is left alone - the client is finalizing what it already holds`() {
        assertEquals(ErrorType.RATE_LIMIT, afterContent(rateLimit))
        assertEquals(ErrorType.API_ERROR, afterContent(apiError))
    }

    @Test
    fun `the endings only a person can act on stop the turn in band`() {
        // A retry of identical bytes cannot change any of these, so relabelling would be a lie that spends the
        // client's retry budget on a wall. Slice 4: they read the in-band class the client stops on, and the person
        // reads splice's line, which says what to do. The client's own login keeps authentication_error, the one
        // type that reaches its sign-in.
        assertEquals(ErrorType.INVALID_REQUEST, beforeContent(FailureCause.Provider.Rejected(staleReasoning = false)))
        assertEquals(ErrorType.INVALID_REQUEST, beforeContent(FailureCause.Provider.AuthRejected(LoginOwner.SPLICE)))
        assertEquals(ErrorType.AUTHENTICATION, beforeContent(FailureCause.Provider.AuthRejected(LoginOwner.CLIENT)))
    }

    @Test
    fun `a type the client already retries rides through untouched`() {
        assertEquals(ErrorType.OVERLOADED, beforeContent(FailureCause.Provider.Overloaded(Lift.Unstated, false)))
        // Slice 4, ruling 4: splice's own budget is a permanent ending with no login status, so it reads the in-band
        // class the client stops on rather than the permission_error it wore before.
        val budget = Decline.Budget(limit = "\$20.00", resetsAt = Instant.parse("2026-10-27T05:00:00Z"))
        assertEquals(ErrorType.INVALID_REQUEST, beforeContent(FailureCause.Splice.Declined(budget)))
    }

    @Test
    fun `a PERMANENT pre-content failure stops the turn in band`() {
        // The operator law "always a retry armed" is about failures a retry can HEAL. A permanent
        // failure is not one of those: the identical bytes produce the identical verdict, and
        // RetryPolicy arms a cooldown only for RATE_LIMITED, so with CLAUDE_CODE_RETRY_WATCHDOG=1
        // the relabel costs up to 300 client re-sends at four upstream attempts each — for an answer
        // that cannot move. It keeps its type, and the client ends the session on the honest
        // verdict instead of grinding. This is the row's finding 1: before it, permanence reached
        // nothing but the `partial` field, so a deterministic refusal or an invalid_parameter was
        // advertised as transient like any other API_ERROR. Slice 4: the type it keeps is invalid_request_error,
        // the in-band class the record shows the client prints and does not send again.
        assertEquals(ErrorType.INVALID_REQUEST, beforeContent(FailureCause.Provider.Unrecognized(clientError = true)))
    }

    @Test
    fun `a rate limit is retryable in band even when the classifier called it non-transient`() {
        // THE MEASURED TRAP, and the reason this cell exists at all: the shared classifier, until
        // failure-handling slice 3 read each head's replies with its own reader, marked a 429
        // (and V4-73's 403 spend-limit) transient = FALSE, because `transient` gates RE-ANCHORING —
        // whether re-POSTing the identical full context is worth it — which is a different question
        // from whether the client should re-send. A quota window is a condition that CHANGES WITH
        // TIME, which is the one thing a permanent failure cannot be, so rate limit wins the
        // argument and the type still moves. The first draft of V4-81's plumbing tested permanence
        // as "!transient" and would have silently reverted V4-71 on every rate-limited turn;
        // RetryAlwaysArmedTest's sweep is what catches that, and this cell says why. The leaf
        // carries that permanence itself now, so the cell asks with it and checks it is there.
        assertTrue(rateLimit.permanent)
        assertEquals(ErrorType.OVERLOADED, beforeContent(rateLimit))
    }

    @Test
    fun `permanence changes nothing once content has reached the client`() {
        // The two gates are independent and BOTH must pass before the type moves: content already
        // delivered excludes the remap on its own, so permanence is not what is protecting the
        // after-content case — worth pinning, because a reader could otherwise take the new
        // parameter as the reason.
        assertEquals(ErrorType.API_ERROR, afterContent(FailureCause.Provider.Unrecognized(clientError = true)))
        assertEquals(ErrorType.API_ERROR, afterContent(apiError))
    }
}
