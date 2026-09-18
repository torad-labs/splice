// NEW: V4-102 (arch-audit, 2026-09-17) — every outcome tag, defined ONCE.
//
// These strings are written to the perf JSONL and read back by the window summary, the doctor and
// the statusline, so a tag is a CONTRACT between a writer and a reader in different modules. Before
// this file that contract was 18 string literals scattered across gateway and control — each one a
// chance for a writer to spell a tag the reader never matches, which fails as a silently missing
// row rather than as a compile error. The single-source wall exists for exactly that class.
//
// WHY THE LITERALS LIVE HERE AND NOWHERE ELSE: the wall scopes itself to gateway/** and control/**
// (see kt-outcome-tag-single-source.yml files:), so core is the one place a literal is legal. That
// is not a loophole — it is the point. One file owns the spelling, everything else refers to it,
// and a new tag is added here rather than retyped at a call site.
//
// COMPARISON, NOT JUST CONSTRUCTION: a reader that compares `row.outcome == "empty_model"` is the
// same defect one hop later, which is why the rule matches bare literals as well as put() arguments.
// PREFER THE CONSTANT for both directions.
//
// The `object` below is deliberate rather than a companion: the walls forbid companion objects, and
// the parameterised tags (failure:<wire>, error:<kind>) cannot be enum constants because their
// suffix is not known until the call site.
package splice.core.perf

import splice.core.turn.ErrorType

/** Every fixed outcome tag. [wire] is what the JSONL and the journal carry verbatim. */
public enum class OutcomeTag(public val wire: String) {
    OK("ok"),
    CLIENT_ABORT("client_abort"),
    EMPTY_MODEL("empty_model"),
    EMPTY_COMPACT("empty_compact"),
    EMPTY_MESSAGE("empty_message"),
    CANCELLED("error:cancelled"),
    UNEXPECTED("error:unexpected"),
    RATE_LIMITED("error:rate-limited"),
    ALL_ACCOUNTS_EXHAUSTED("error:all-accounts-exhausted"),
    AUTH_MISSING("error:auth-missing"),
    UPSTREAM_FAILED("error:upstream-failed"),
    UPSTREAM_FRAME_TOO_LARGE("error:upstream-frame-too-large"),
}

/** The parameterised tags, whose suffix only the call site knows.
 *
 *  A separate `object` rather than a companion on [OutcomeTag]: the walls forbid companions, and a
 *  member function would need an instance of an enum constant to call, which reads as though the
 *  choice of constant mattered when it does not. */
public object OutcomeTags {

    /** `failure:<wire>` — a turn that ended on a typed Failure. */
    public fun failure(type: ErrorType): String = FAILURE_PREFIX + type.wireName

    /** `error:<kind>` — a locally-classified refusal whose kind is a fixed string. */
    public fun error(kind: String): String = ERROR_PREFIX + kind
}

// The two prefixes, kept private so the only way to build a tag is through the helpers above.
private const val FAILURE_PREFIX = "failure:"
private const val ERROR_PREFIX = "error:"
