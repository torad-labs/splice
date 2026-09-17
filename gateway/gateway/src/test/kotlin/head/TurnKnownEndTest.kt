// NEW (V4-78): the PRE-CONTENT WIRE-TYPE RULE, pinned as a table.
//
// The rule lives in PreContentWireType — SseEmitter.kt since V4-81, which moved it from the head's
// failure surfaces to the one place an error frame is written — and this pins every row of it,
// because the interesting content of the rule is not what it remaps but what it REFUSES to: the two
// types the client would treat as terminal in band become the one it retries, everything already-
// retryable rides through, a PERMANENT failure keeps its real type, and the two types that mean
// "only the operator can fix this" are never touched.
//
// The E2E half of this row lives next door: RetryAlwaysArmedTest's sweep drives a live
// api_error-in-200 path and asserts the body the CLIENT receives, and HeadServerFailureBranchTest
// asserts the same path's message and perf row are unchanged by the relabel.
package head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.gateway.wire.PreContentWireType

class TurnKnownEndTest {

    @Test
    fun `a pre-content rate limit or api error is wired as overloaded - V4-78`() {
        // Claude Code 2.1.257 retries an IN-BAND error only when the body carries overloaded_error
        // (a 429/529 is retried by STATUS), so these two would otherwise end the session where a
        // retry heals it. Before content, nothing the client has read is at stake.
        assertEquals(ErrorType.OVERLOADED, PreContentWireType.of(ErrorType.RATE_LIMIT, contentReachedClient = false))
        assertEquals(ErrorType.OVERLOADED, PreContentWireType.of(ErrorType.API_ERROR, contentReachedClient = false))
    }

    @Test
    fun `after content the type is left alone - the client is finalizing what it already holds`() {
        assertEquals(ErrorType.RATE_LIMIT, PreContentWireType.of(ErrorType.RATE_LIMIT, contentReachedClient = true))
        assertEquals(ErrorType.API_ERROR, PreContentWireType.of(ErrorType.API_ERROR, contentReachedClient = true))
    }

    @Test
    fun `the types only the operator can fix are never remapped - V4-78 bounds`() {
        // A retry of identical bytes cannot change either of these, so relabelling would be a lie
        // that spends the client's retry budget on a wall.
        assertEquals(
            ErrorType.INVALID_REQUEST,
            PreContentWireType.of(ErrorType.INVALID_REQUEST, contentReachedClient = false),
        )
        assertEquals(
            ErrorType.AUTHENTICATION,
            PreContentWireType.of(ErrorType.AUTHENTICATION, contentReachedClient = false),
        )
    }

    @Test
    fun `a type the client already retries rides through untouched`() {
        assertEquals(ErrorType.OVERLOADED, PreContentWireType.of(ErrorType.OVERLOADED, contentReachedClient = false))
        assertEquals(ErrorType.PERMISSION, PreContentWireType.of(ErrorType.PERMISSION, contentReachedClient = false))
    }

    @Test
    fun `a PERMANENT pre-content failure keeps its real type - V4-81`() {
        // The operator law "always a retry armed" is about failures a retry can HEAL. A permanent
        // failure is not one of those: the identical bytes produce the identical verdict, and
        // RetryPolicy arms a cooldown only for RATE_LIMITED, so with CLAUDE_CODE_RETRY_WATCHDOG=1
        // the relabel costs up to 300 client re-sends at six upstream attempts each — for an answer
        // that cannot move. It keeps its type, and the client ends the session on the honest
        // verdict instead of grinding. This is the row's finding 1: before it, permanence reached
        // nothing but the `partial` field, so a deterministic refusal or an invalid_parameter was
        // advertised as transient like any other API_ERROR.
        assertEquals(
            ErrorType.API_ERROR,
            PreContentWireType.of(ErrorType.API_ERROR, contentReachedClient = false, permanent = true),
        )
    }

    @Test
    fun `a rate limit is retryable in band even when the classifier called it non-transient`() {
        // THE MEASURED TRAP, and the reason this cell exists at all: ClassifiedFailure marks a 429
        // (and V4-73's 403 spend-limit) transient = FALSE, because `transient` gates RE-ANCHORING —
        // whether re-POSTing the identical full context is worth it — which is a different question
        // from whether the client should re-send. A quota window is a condition that CHANGES WITH
        // TIME, which is the one thing a permanent failure cannot be, so rate limit wins the
        // argument and the type still moves. The first draft of V4-81's plumbing tested permanence
        // as "!transient" and would have silently reverted V4-71 on every rate-limited turn;
        // RetryAlwaysArmedTest's sweep is what catches that, and this cell says why.
        assertEquals(
            ErrorType.OVERLOADED,
            PreContentWireType.of(ErrorType.RATE_LIMIT, contentReachedClient = false, permanent = true),
        )
    }

    @Test
    fun `permanence changes nothing once content has reached the client`() {
        // The two gates are independent and BOTH must pass before the type moves: content already
        // delivered excludes the remap on its own, so permanence is not what is protecting the
        // after-content case — worth pinning, because a reader could otherwise take the new
        // parameter as the reason.
        assertEquals(
            ErrorType.API_ERROR,
            PreContentWireType.of(ErrorType.API_ERROR, contentReachedClient = true, permanent = false),
        )
    }
}
