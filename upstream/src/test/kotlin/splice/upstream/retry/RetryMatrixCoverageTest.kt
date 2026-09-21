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
// No package declaration, matching every sibling test in this directory: `internal` is scoped to
// the MODULE, not the package, and this test source set is a friend of main, so the matrix is
// reachable without one. Declaring a package here would also put the file's location out of step
// with it, which detekt reds as InvalidPackageDeclaration.
package splice.upstream.retry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.WireType

class RetryMatrixCoverageTest {

    @Test
    fun `every cause and phase pair answers with a written reason`() {
        val silent = mutableListOf<String>()
        var pairs = 0
        for (cause in FailureCause.entries) {
            for (phase in FailurePhase.entries) {
                pairs++
                if (RetryMatrix.of(cause, phase).reason.isBlank()) silent += "$cause x $phase"
            }
        }
        assertEquals(
            FailureCause.entries.size * FailurePhase.entries.size,
            pairs,
            "the loop must enumerate the full cause x phase product, not a subset",
        )
        assertTrue(silent.isEmpty(), "pairs answering with no written reason: $silent")
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
    fun `the reason says which filter emptied the ladder`() {
        // A cause healable by nothing and a cause whose window had already closed both return zero
        // layers, and they are opposite facts about the turn — the first is permanent, the second is
        // merely late. If the sentences do not differ, an operator cannot tell them apart, which is
        // the same class of defect as the free-text cause this row exists to remove.
        val permanent = RetryMatrix.of(FailureCause.MODEL_REFUSED, FailurePhase.MID_OUTPUT)
        val late = RetryMatrix.of(FailureCause.UPSTREAM_TRUNCATED, FailurePhase.TERMINAL)
        assertTrue(late.layers.isEmpty(), "a truncation at TERMINAL has no layer left, got ${late.layers}")
        assertTrue(
            late.reason.contains("At this phase"),
            "a phase-narrowed ladder must say so in its reason, got: ${late.reason}",
        )
        assertFalse(
            permanent.reason.contains("At this phase"),
            "a cause healable by nothing is not phase-narrowed, got: ${permanent.reason}",
        )
    }

    @Test
    fun `every cause and phase pair names a wire type`() {
        // WireType.of uses getValue, so a cause added without a base class throws here rather than
        // defaulting to something plausible — the same fail-closed shape as the ceiling map.
        for (cause in FailureCause.entries) {
            for (phase in FailurePhase.entries) {
                assertNotNull(WireType.of(cause, phase), "$cause at $phase has no wire type")
            }
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
                    WireType.of(cause, phase) == ErrorType.INVALID_REQUEST,
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
                WireType.of(FailureCause.INTERNAL, phase),
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
                    WireType.of(cause, phase),
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
        assertEquals(ErrorType.OVERLOADED, WireType.of(FailureCause.UPSTREAM_REPORTED, FailurePhase.CONNECT))
        assertEquals(ErrorType.OVERLOADED, WireType.of(FailureCause.UPSTREAM_REPORTED, FailurePhase.FIRST_BYTE))
        assertEquals(ErrorType.API_ERROR, WireType.of(FailureCause.UPSTREAM_REPORTED, FailurePhase.MID_OUTPUT))
        assertEquals(ErrorType.API_ERROR, WireType.of(FailureCause.UPSTREAM_REPORTED, FailurePhase.TERMINAL))
    }
}
