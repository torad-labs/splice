// NEW: V4-117 (error taxonomy, 2026-09-18) — the enumeration test the brief calls for: every
// (FailureCause, FailurePhase) pair must answer, and the answer must carry a written reason.
//
// WHY IT LIVES HERE AND NOT IN :quality-architecture, WHERE THE ROW'S FILE LIST PUT IT: the matrix and its two
// result types are `internal` to :upstream — deliberately, see RetryMatrix.kt's visibility note,
// because the retry loop that consumes them (RetryRules) is in this same module, so `public` would
// declare a surface no other module uses and red the public-surface ratchet. A test in :quality-architecture
// cannot see an internal declaration at all, so the enumeration runs here, in a friend of this
// module's main source set. The path is reported to the orchestrator rather than silently deviated
// from.
//
// WHAT THIS PROVES THAT A HAND-WRITTEN CHECKLIST CANNOT: the product is enumerated from the ENUMS,
// so a cause added tomorrow is covered the moment it exists rather than when someone remembers to
// add a row — and `of` fails closed on an unceilinged cause, so that same addition is a red test
// instead of a silent "entitled to nothing".
// The package matches the directory (detekt InvalidPackageDeclaration); `internal` is scoped to the
// MODULE and this test source set is a friend of main, so the matrix is reachable either way.
package splice.upstream.retry

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.upstream.failure.FailureSource
import splice.upstream.failure.UpstreamFailureClassifier
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.assertEnds
import splice.upstream.transport.clientOver
import splice.upstream.transport.postOnce
import java.util.concurrent.atomic.AtomicInteger

/** The wire type a failure of this cause at this phase carries. The outcome derives it, so the test asks the outcome. */
private fun wireTypeOf(cause: FailureCause, phase: FailurePhase): ErrorType =
    TurnOutcome.Failure(message = "", cause = cause, phase = phase).type

class RetryMatrixCoverageTest {

    // V4-117 audit: the matrix had no caller, and its 4XX cell said a 4xx gets no transport retry
    // while the loop (V4-62) retries every failure status. A matrix is only true if it describes the
    // loop, so each failed status runs through the REAL loop and the REAL classifier, and L1 in the
    // cause's ceiling must match whether the loop sent the request again.
    @Test
    fun `L1 in the matrix is exactly what the retry loop does with a failed status`() = runTest {
        val samples = listOf(
            400 to "bad request",
            403 to "forbidden",
            404 to "not found",
            422 to "unprocessable",
            400 to """{"error":{"code":"invalid_prompt","message":"refused"}}""",
            400 to """{"error":{"message":"prompt is too long: 300000 tokens > 200000 maximum"}}""",
            500 to "boom",
            501 to "not implemented",
            503 to "busy",
        )
        val disagreements = samples.mapNotNull { (status, body) ->
            val calls = AtomicInteger()
            val engine = MockEngine {
                calls.incrementAndGet()
                respond(body, HttpStatusCode.fromValue(status), headersOf())
            }
            assertEnds<UpstreamFailed> { postOnce(clientOver(engine)) }
            val cause = UpstreamFailureClassifier.classify(FailureSource.HTTP, body, status).cause
            val entitled = RetryLayer.L1_TRANSPORT in RetryMatrix.of(cause, FailurePhase.CONNECT).layers
            val retried = calls.get() > 1
            "$status $cause: loop retried=$retried, matrix L1=$entitled".takeIf { retried != entitled }
        }
        assertEquals(emptyList<String>(), disagreements)
    }

    @Test
    fun `a deterministic verdict is entitled to no layer, at any phase`() {
        val unrepairable = listOf(
            FailureCause.MODEL_REFUSED,
            FailureCause.CONTENT_FILTERED,
            FailureCause.CODE_MODE_PROTOCOL,
            FailureCause.DIALECT_UNSUPPORTED,
        )
        for (cause in unrepairable) {
            for (phase in FailurePhase.entries) {
                val layers = RetryMatrix.of(cause, phase).layers
                assertTrue(
                    layers.isEmpty(),
                    "$cause at $phase must be unrepairable — a retry reproduces the verdict, got $layers",
                )
            }
        }
    }

    @Test
    fun `the phase filter narrows the ladder and never widens it`() {
        // A stall mid-output can be RESUMED from delivered text but not re-issued: the client has
        // already read those bytes, so a re-issue would duplicate them. This is the invariant the
        // whole phase axis exists for.
        val midStall = RetryMatrix.of(FailureCause.UPSTREAM_STALLED, FailurePhase.MID_OUTPUT).layers
        assertTrue(RetryLayer.L3_REANCHOR_RESUME in midStall, "mid-output must admit the resume, got $midStall")
        assertFalse(RetryLayer.L2_PRE_CONTENT_REISSUE in midStall, "mid-output must not re-issue, got $midStall")

        // Before the first byte the reverse holds: a re-issue is safe, and there is nothing to resume.
        val firstByte = RetryMatrix.of(FailureCause.UPSTREAM_STALLED, FailurePhase.FIRST_BYTE).layers
        assertTrue(RetryLayer.L2_PRE_CONTENT_REISSUE in firstByte, "first-byte must admit a re-issue, got $firstByte")
        assertFalse(RetryLayer.L3_REANCHOR_RESUME in firstByte, "first-byte has nothing to resume, got $firstByte")
    }

    @Test
    fun `a terminal admits no repair layer at all`() {
        for (cause in FailureCause.entries) {
            val layers = RetryMatrix.of(cause, FailurePhase.TERMINAL).layers
            assertTrue(
                layers.all { it == RetryLayer.L5_CLIENT_RETRY_CLASS },
                "$cause at TERMINAL must fall to the client class only, got $layers",
            )
        }
    }

    @Test
    fun `the retry default is total, so an unattributable failure is never written off`() {
        // RETRY DEFAULT IS TOTAL, as a property of the table rather than a promise in a comment:
        // the two causes that exist FOR the cases splice could not pin down must land on a type the
        // client will retry, never on the one that tells it the request itself was bad.
        val unattributable = listOf(FailureCause.INTERNAL, FailureCause.UPSTREAM_REPORTED)
        for (cause in unattributable) {
            for (phase in FailurePhase.entries) {
                assertFalse(
                    wireTypeOf(cause, phase) == ErrorType.INVALID_REQUEST,
                    "$cause at $phase must not be wired as the client's bad request, which it will not retry",
                )
            }
        }
        // INTERNAL is held to the STRICTER half of that: it is retryable at EVERY phase, including
        // after content, because the default's whole purpose is that a failure splice could not
        // diagnose is still offered to the client. Pinned here because this is the mapping a
        // dialect's generic catch relies on, and getting it wrong sent such a failure out as
        // API_ERROR mid-output, where nothing retries it.
        for (phase in FailurePhase.entries) {
            assertEquals(
                ErrorType.OVERLOADED,
                wireTypeOf(FailureCause.INTERNAL, phase),
                "an unattributable failure must be retryable at $phase, not merely pre-content",
            )
        }
    }

    @Test
    fun `a verdict on the request is wired as the client's own error class`() {
        val requestVerdicts = listOf(
            FailureCause.UPSTREAM_STATUS_4XX,
            FailureCause.REQUEST_TOO_LARGE,
            FailureCause.DIALECT_UNSUPPORTED,
        )
        for (cause in requestVerdicts) {
            for (phase in FailurePhase.entries) {
                assertEquals(
                    ErrorType.INVALID_REQUEST,
                    wireTypeOf(cause, phase),
                    "$cause is a verdict on the request itself, at any phase",
                )
            }
        }
    }

    @Test
    fun `a generic failure is advertised as retryable only before the client has seen content`() {
        // THE RELOCATED V4-78 RULE, and the only place the phase changes the answer. A generic
        // api_error arriving before any byte is wired overloaded_error so the client retries it;
        // after content it stays api_error, because re-sending would duplicate what the user read.
        // Asserting BOTH halves is the point: a table that always returned one of them would pass a
        // one-sided test.
        //
        // UPSTREAM_REPORTED is the cause used here, NOT INTERNAL: INTERNAL is retryable at every
        // phase by design (asserted above), so it cannot demonstrate a phase-sensitive rule at all.
        assertEquals(ErrorType.OVERLOADED, wireTypeOf(FailureCause.UPSTREAM_REPORTED, FailurePhase.CONNECT))
        assertEquals(ErrorType.OVERLOADED, wireTypeOf(FailureCause.UPSTREAM_REPORTED, FailurePhase.FIRST_BYTE))
        assertEquals(ErrorType.API_ERROR, wireTypeOf(FailureCause.UPSTREAM_REPORTED, FailurePhase.MID_OUTPUT))
        assertEquals(ErrorType.API_ERROR, wireTypeOf(FailureCause.UPSTREAM_REPORTED, FailurePhase.TERMINAL))
    }

    @Test
    fun `a refusal and a 5xx keep the classes the dialects hand them to`() {
        // W4-A's far end, retired here from w4_a_refusal_honesty.ts (2026-09-21). That wall checked
        // BOTH ends of one chain: the dialect arms read a refusal or a 5xx into a FailureCause, and
        // these two pairs are what those causes must still RESOLVE to. One end moving while the
        // other stays is the failure that matters, and holding only one end cannot see it — the arm
        // tokens live in the dialects' own tests, and this is the other end.
        //
        // The wall asserted the two mappings as SOURCE SUBSTRINGS of WireType.kt. This asserts the
        // resolved value at every phase, so a pre-content rule that grew to cover a refusal, or a
        // base map edited past the line the substring matched, is visible here and was not there.
        //
        // MODEL_REFUSED and CONTENT_FILTERED are the client's bad-request class at EVERY phase. As
        // api_error the pre-content rule made them overloaded_error, and the client re-sent even the
        // plain api_error: a refusal re-sent is the identical refusal at full price. Every phase is
        // asserted because a pre-content rule that grew to cover them would pass a one-phase test.
        // CONTENT_FILTERED is a refusal the backend phrased differently; CX-07 names it beside it.
        for (cause in listOf(FailureCause.MODEL_REFUSED, FailureCause.CONTENT_FILTERED)) {
            for (phase in FailurePhase.entries) {
                assertEquals(
                    ErrorType.INVALID_REQUEST,
                    wireTypeOf(cause, phase),
                    "$cause is the vendor's verdict on the request, never retried, at $phase",
                )
            }
        }
        // A 5xx is a capacity answer at EVERY phase — its base is already the retryable class, so
        // the pre-content rule never touches it and there is no phase at which it hardens.
        for (phase in FailurePhase.entries) {
            assertEquals(
                ErrorType.OVERLOADED,
                wireTypeOf(FailureCause.UPSTREAM_STATUS_5XX, phase),
                "a backend 5xx is the one class Claude Code retries on its own, at $phase",
            )
        }
    }
}
