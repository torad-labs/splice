// NEW: V4-122 — the economics retention window, declared once for the two surfaces that must agree
// on it.
//
// WHY IT IS IN CORE: the window is written in milliseconds by the store that trims against it and in
// hours by the payload that reports it to the console, and those two live in modules with no
// dependency edge between them (:daemon-head/usage and :daemon-control/api) — so neither can import the
// other's declaration. A prose comment was doing the job instead, which is the EQUAL-BY-COMMENT
// class: "Mirrors EconomicsStore's RETENTION_MS (8 days)" asserts an equality that nothing enforces,
// and a comment is not a wall. Core is the lowest module both reach.
//
// HOURS IS DERIVED, NOT RESTATED: 192 is what 8 days is, so writing it out is a second declaration
// of the same window in different units. Deriving it means the two can never disagree, and the
// rounding is exact because the divisor divides the window without a remainder.
package splice.core.perf

/** 8 days: one full weekly quota window plus a day of overlap, so the week-to-date figure stays
 *  exact across a reset boundary instead of losing its head. */
public const val ECONOMICS_RETENTION_MS: Long = 8L * 24 * 60 * 60 * 1000

/** The same window in the unit the console reads it in — the UI needs the window it is reading over
 *  to size the week bar honestly when the buckets do not yet fill it. */
public const val ECONOMICS_RETENTION_HOURS: Long = ECONOMICS_RETENTION_MS / 3_600_000
