// NEW: Nov 10, 2026 — Claude Code's own timers and retry limits, which a launch plants so the head's wall decides every
// turn, moved out of LaunchService.buildEnv when the Nov 8-9 non-streaming fix added two more of them (concentration).
// All four are planted UNCONDITIONALLY, native included: no head wants a client timer deciding a turn the head's wall
// already bounds, and every head wants its session to survive a hold.
package splice.launch.recipe

import splice.launch.LaunchSpec

internal object LaunchClientTimers {
    fun of(spec: LaunchSpec): Map<String, String> = buildMap {
        // Claude Code's default request timeout is 600s and it also bounds the first-byte
        // deadline; the head's whole-turn cap is the wall that decides a turn, so the client
        // must outlive it (spec.gateway.apiTimeoutMs = cap + grace). Without this every compaction
        // longer than ten minutes died as client_abort while the daemon was still serving it.
        // The head's own wall owns this deadline, so an ambient API_TIMEOUT_MS is replaced rather than
        // merged (unlike NO_PROXY); a larger value buys nothing past totalCap and a smaller one recreates
        // the abort.
        // Claude Code's non-streaming fallback (after a streaming error it re-sends the turn
        // with stream:false under this same deadline) stays ENABLED on purpose: the collect
        // path answers it silently until the terminal body, and with the deadline past totalCap
        // the head's wall speaks first. CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK would only
        // delete a recovery path.
        put("API_TIMEOUT_MS", spec.gateway.apiTimeoutMs.toString())
        // Nov 8-9: Claude Code cut every non-streaming request through splice at about 360 s and re-sent it 228
        // times, so the deadline above never got to decide. The cut is Bun's own fetch timeout, not the client's
        // (measured on Bun 1.4.3: a server that sends nothing is cut at 360363 ms with the default options, and
        // not through 540 s with timeout: false), and the non-streaming path writes no byte until the whole
        // answer is ready. The client passes timeout: false to fetch only when its body-idle watchdog is on or
        // this variable is falsy, and "0" is falsy: planted, the head's own deadline answers a non-streaming
        // request first, as the comment above intends.
        put("API_FORCE_IDLE_TIMEOUT", "0")
        // V4-72: PERSISTENT RETRY. Without this the client gives up after its default 10 retries
        // (~2-3 min of backoff), so a rate-limit hold longer than that ENDS THE SESSION instead
        // of resuming when the window reopens. Verified in the Claude Code 2.1.257 binary: QI()
        // reads CLAUDE_CODE_RETRY_WATCHDOG, and shouldRetry then returns true for status 429 or
        // overloaded_error BEFORE the claude.ai-subscriber 429 gate — which is why it also
        // covers the native head, whose client runs in subscriber mode. In this mode the 429
        // sleep is sao(): anthropic-ratelimit-unified-reset epoch minus now, capped at 6h, other
        // transient errors get 300 retries instead of 10, and the retry-after-too-long abort
        // (a NON-persistent client aborts above 60s) is skipped. splice already sends the
        // unified-reset header on its admission 429 (V4-51), so that sleep lands exactly on the
        // cooldown lift. It is a client-side WAIT policy.
        put("CLAUDE_CODE_RETRY_WATCHDOG", "1")
        // Claude Code's limit for re-sending a non-streaming request that timed out is unlimited while this is
        // unset. 2 is the limit the client itself applies when its retry watchdog judges a failed stream
        // outlasted the timeout. It bounds only the retries the client COUNTS, and the client counts one only
        // when its own timer fired (API_TIMEOUT_MS). It does NOT bound a cut by Bun's built-in fetch timeout at
        // about 360 s, which the client does not count: that is what re-sent one request 228 times over 25 hours
        // on Nov 8-9, and API_FORCE_IDLE_TIMEOUT above removes it. Every head's session wants the loop it does
        // count bounded.
        put("CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES", "2")
    }
}
