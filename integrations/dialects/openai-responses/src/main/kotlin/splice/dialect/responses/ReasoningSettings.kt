// NEW: how a head's reasoning is displayed, replayed and requested, as the one value the Responses
// providers take instead of four loose parameters (display, replay, effort, summary) that every
// provider, the parts wiring and the turn options passed through together.
package splice.dialect.responses

import splice.core.turn.ReasoningDisplay

/**
 * [display] is whether reasoning reaches the client; [replay] the LEGACY client-round-trip replay
 * (redacted_thinking through Claude Code), operator opt-in only; [effort] and [summary] the
 * config-driven request defaults (TOML / env / state), either absent when the operator set none.
 */
public class ReasoningSettings(
    public val display: ReasoningDisplay,
    public val replay: Boolean,
    public val effort: String?,
    public val summary: String?,
) {
    /** Whether reasoning is shown, which is also when the encrypted handle and a summary are asked for. */
    public fun visible(): Boolean = !display.isOff

    /** The summary a request carries: the configured one while reasoning is shown, else `none`. */
    public fun summaryForRequest(): String? = if (visible()) summary else "none"
}
