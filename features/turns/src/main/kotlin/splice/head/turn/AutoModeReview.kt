// NEW (Nov 14, 2026): Claude Code's auto-mode review, recognized so a head's own instructions stay out of it.
//
// In auto mode Claude Code asks the session's model, before an action runs, whether a policy blocks it. That request
// carries no Claude Code prompt: its system text is the policy itself, which opens with [REVIEW_OPENING] (read from the
// 2.1.325 binary; the gateway protocol page says the review skips the rest of Claude Code's system prompt). A head's
// standing prompt layers and a team slot's text were written for a coding agent. Appended to the policy they ask the
// reviewer to keep working instead of judging, and a head in replace mode put its own prompt where the policy was, so
// the reviewer judged with no policy at all. The review now reaches the upstream with the policy Claude Code wrote.
//
// The match is the policy's opening sentence, as ActivityLabel matches its side query. A later Claude Code that
// rewords it is counted as a near miss on the turn's perf row ([AUTO_MODE_REVIEW_NEAR_MISS]) instead of going unseen.
package splice.head.turn

import splice.core.perf.TurnPerf
import splice.core.wire.AnthropicRequest

/** Whether a request is Claude Code's auto-mode review, said on the turn's perf row either way it is found. */
internal class AutoModeReview {

    /** True for the review. A request that only resembles it gets [AUTO_MODE_REVIEW_NEAR_MISS] and false. */
    fun recognized(request: AnthropicRequest, perf: TurnPerf): Boolean {
        val system = request.system?.trimStart() ?: return false
        if (system.startsWith(REVIEW_OPENING)) {
            perf.setCount(AUTO_MODE_REVIEW, 1L)
            return true
        }
        val lowered = system.take(NEAR_MISS_WINDOW_CHARS).lowercase()
        if (NEAR_MISS_PHRASES.all { it in lowered }) perf.setCount(AUTO_MODE_REVIEW_NEAR_MISS, 1L)
        return false
    }
}

/** Set to 1 on a turn that was Claude Code's auto-mode review, which went upstream without the head's prompt layers
 *  or a slot's text. */
internal const val AUTO_MODE_REVIEW: String = "auto_mode_review"

/** Set to 1 on a turn whose system text reads like the auto-mode review's policy but does not open with its sentence:
 *  a Claude Code that reworded it, whose reviews now carry the head's layers again. */
internal const val AUTO_MODE_REVIEW_NEAR_MISS: String = "auto_mode_review_near_miss"

// why: the policy's first sentence, verbatim from Claude Code 2.1.325's auto-mode template.
private const val REVIEW_OPENING = "You are a security monitor for autonomous AI coding agents."

// why: a reworded opening still names what the reviewer is in its first lines; a coding prompt that mentions a
// security monitor further down is not the review.
private const val NEAR_MISS_WINDOW_CHARS = 400

private val NEAR_MISS_PHRASES = listOf("security monitor", "autonomous")
