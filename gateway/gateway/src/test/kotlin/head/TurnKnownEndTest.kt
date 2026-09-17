// NEW (V4-78): the PRE-CONTENT WIRE-TYPE RULE, pinned as a table.
//
// The rule lives in PreContentWireType (TurnKnownEnd.kt) and this pins every row of it, because the
// interesting content of the rule is not what it remaps but what it REFUSES to: the two types the
// client would treat as terminal in band become the one it retries, everything already-retryable
// rides through, and the two types that mean "only the operator can fix this" are never touched.
//
// The E2E half of this row lives next door: RetryAlwaysArmedTest's sweep drives a live
// api_error-in-200 path and asserts the body the CLIENT receives, and HeadServerFailureBranchTest
// asserts the same path's message and perf row are unchanged by the relabel.
package head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.gateway.head.PreContentWireType

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
}
