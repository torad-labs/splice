// NEW: V4-122 — how deep splice will recurse into CLIENT-AUTHORED JSON, declared once.
//
// WHY THIS IS SHARED AND THE OTHER DEPTH_CAP IS NOT: three files declared `DEPTH_CAP` and they were
// two different budgets wearing one name. This is the pair that guards the SAME invariant — both are
// walks over untrusted client JSON that must never StackOverflowError outside every sanctioned
// catch — and LoopGuard.kt's own comment already said so ("Same 200-depth invariant as
// PassthroughCacheControl's placement walk") while the number was written out twice. The third,
// MfjsSanitizer's 10, is a SCHEMA-nesting bound with a different purpose and a different reaction to
// its cap, so it keeps its own private declaration under a name that says which budget it is.
//
// THE VALUE IS A SAFETY BOUND, NOT A POLICY: far above any legitimate request's nesting and well
// below a stack-overflow depth, so the two callers react to it differently on purpose — the
// cache-control walk passes the element through unchanged, the canonicaliser replaces the subtree
// with a marker — and neither reaction needs the other's. What they must agree on is the depth at
// which they stop.
package splice.core.wire

/** The deepest a client-authored JSON payload is walked before the walk stops descending. */
public const val CLIENT_JSON_DEPTH_CAP: Int = 200
