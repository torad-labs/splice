// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 1) — what one launch offers as /fast and /ultrafast, and what that
// changes in the session's environment.
package splice.launch.recipe

import splice.core.model.ServiceTier

/** Claude Code's switch for its own (Anthropic's) fast mode and /fast. */
private const val DISABLE_FAST_MODE_ENV = "CLAUDE_CODE_DISABLE_FAST_MODE"

/** [tiers]: the tiers a head signed in with ChatGPT offers (possibly none, when its model's catalog entry lists
 *  none); null for any other head, which keeps Claude Code's own /fast. */
internal data class SpeedOffer(val tiers: List<ServiceTier>?) {
    /** Claude Code's own fast mode is Anthropic's, so a session that offers splice's /fast turns it off and never sends
     *  Anthropic's speed setting to a GPT model. Any other launch leaves the variable as the person's environment has
     *  it, so Claude Code's own /fast stays theirs to keep or turn off. */
    fun environment(environment: LaunchEnvironment): LaunchEnvironment = if (tiers == null) {
        environment
    } else {
        environment.copy(env = environment.env + (DISABLE_FAST_MODE_ENV to "1"))
    }
}
