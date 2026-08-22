// PORT-OF: UsageHud.kt @ d8653a0 — invariants unchanged: makeOutputClamp clamps REPORTED output to
// the client's max_tokens (backend rejects cap params; reasoning tokens count in output — v26),
// moved verbatim onto its own collaborator (HD-24, 2026-08-17). This was UsageHud's only member
// needing LogSink, so moving it drops splice.core.util off UsageHud entirely.
package splice.gateway.usage

import splice.core.util.LogSink

/**
 * Clamps a REPORTED `output_tokens` count down to the client's `max_tokens` (v26).
 *
 * A reporting-only correction and never a generation cap: the backend rejects the cap params, and
 * reasoning tokens count toward output, so an honest upstream number can legitimately exceed what
 * the client asked for — and a client that sees `output > max_tokens` mis-draws its context bar.
 *
 * Built by [OutputClampPolicy.makeOutputClamp] and consumed by `TurnPipeline.clampOutput`, which
 * were two declarations of the same `(Long) -> Long` with no type in common; the pipeline's
 * parameter is the hud's return value at every call site, so this is ONE role and now one type.
 * Identity (`{ it }`) is the correct clamp for a turn with no client `max_tokens`.
 */
public fun interface OutputClamp {
    public operator fun invoke(outputTokens: Long): Long
}

/** Builds [OutputClamp] instances. `object` (not companion, not top-level fun) mirrors the
 *  sanctioned `UsageWarnPolicy` idiom already in splice.core.usage. */
public object OutputClampPolicy {
    /** Clamp REPORTED output_tokens to the client's max_tokens (v26). */
    public fun makeOutputClamp(
        clientMaxTokens: Long?,
        compact: Boolean,
        headTag: String,
        log: LogSink,
    ): OutputClamp {
        val max = clientMaxTokens?.takeIf { it > 0 }
        return { n ->
            if (max != null && n > max) {
                log("[$headTag] output_tokens $n > client max_tokens $max compact=$compact — clamping reported usage\n")
                max
            } else {
                n
            }
        }
    }
}
