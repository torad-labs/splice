// NEW: how a head's reasoning is displayed, replayed and requested, as the one value the Responses
// providers take instead of four loose parameters (display, replay, effort, summary) that every
// provider, the parts wiring and the turn options passed through together.
package splice.dialect.responses

import splice.core.turn.ReasoningDisplay

/**
 * [display] is whether reasoning reaches the client; [replay] the LEGACY client-round-trip replay
 * (redacted_thinking through Claude Code), operator opt-in only; [effort] and [summary] the
 * config-driven request defaults (TOML / env / state), either absent when the operator set none.
 *
 * [live], when the head has one, restates display, replay and summary as the operator's knobs say they are NOW. Every
 * reader asks [now] once per turn, so a PATCH reaches the next turn and one turn never sees two answers. Effort is not
 * in it: reasoning effort is part of the prompt-cache key, so it stays the value the head was built with.
 */
public class ReasoningSettings(
    public val display: ReasoningDisplay,
    public val replay: Boolean,
    public val effort: String?,
    public val summary: String?,
    private val live: LiveReasoning? = null,
) {
    /** These settings as they stand now: the live reading where the head has one, else this value. */
    public fun now(): ReasoningSettings = live?.invoke() ?: this

    /** Whether reasoning is shown, which is also when the encrypted handle and a summary are asked for. */
    public fun visible(): Boolean = !display.isOff

    /** The summary a request carries: the configured one while reasoning is shown, else `none`. */
    public fun summaryForRequest(): String? = if (visible()) summary else "none"
}

/** The operator's display, replay and summary knobs read again, as the [ReasoningSettings] they make. */
public fun interface LiveReasoning {
    public operator fun invoke(): ReasoningSettings
}
