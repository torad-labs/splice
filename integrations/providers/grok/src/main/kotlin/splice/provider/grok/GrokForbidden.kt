// NEW: what a 403 on a grok credential means: an expiry the refresh fixes, or a wall no refresh or sign-in can.
// Split from GrokAuthProvider.kt, which answers the transport's two questions by asking this.
package splice.provider.grok

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.upstream.retry.MS_PER_S

// xAI overloads one status's auth meaning (403: an expiry AND a billing wall). Since failure-handling slice 3 its own
// reader (GrokReplies) tells the two apart and names the expiry a dead credential, so the freshness judgement below
// arbitrates only that reading. See allowRefreshAfterFailure.
internal class GrokForbidden(
    private val authJson: GrokAuthJson,
    private val clock: WallClock,
    private val log: LogSink,
) {
    /**
     * RULE 1, the structural half (2026-09-16 operator report: the grok login page kept reopening).
     *
     * A 403 on a credential that is DEMONSTRABLY FINE cannot be an expiry, whatever the body says.
     * Vetoing the refresh here kills the whole class at once — no refresh, no dead credential, no
     * sign-in, no browser — and it needs no vendor strings, so the NEXT unrecognised 403 code is
     * covered too.
     *
     * ONLY A REAL `expires` MAY VETO (V4-38 redo). The first version read [GrokAuthJson.readSnapshot],
     * which never returns a null expiry: for a file that carries none (legacy shape, or a foreign CLI
     * write that stripped it) it SYNTHESIZES mtime + 4h (G18/SH-01, GrokAuthJson lines 99-104). That
     * ceiling is a statement about staleness, not about validity, and reading it as proof of freshness
     * suppressed the refresh on a genuine 403 expiry for up to four hours — precisely the 2026-07-18
     * grok-dead-head shape this provider exists to prevent, and the exact inversion
     * SynthesizedExpiry.kt:5 forbids: it "can force an extra refresh, never suppress one".
     * [GrokAuthJson.parseSnapshot] is the seam that already separates the two — it is what
     * readSnapshot calls BEFORE applying the synthesis, and it reports the file's `expires` as null
     * when the file has none — so the veto asks it instead. It also re-reads rather than serving the
     * TTL cache, which is the right side to err on for a judgement made once per auth failure, and it
     * THROWS where readSnapshot classifies, so the catch is here.
     *
     * NEVER BELOW STATUS QUO, on three counts: a token at or inside the proactive window is NOT
     * demonstrably fine; a file with no declared `expires` is NOT demonstrably fine; and an unreadable
     * file proves nothing. All three fall through to the pre-V4-38 behaviour — the refresh runs.
     *
     * RULE 3 no longer appears here: an entitlement body is xAI's reader's to name (GrokReplies), and the person
     * reads the provider's own words in the turn's line. No body reaches the decision below.
     */
    fun allowRefreshAfterFailure(deadCredential: Boolean): Boolean {
        // THE READING xAI'S OWN READER MADE, and only that one (failure-handling slice 3): its expiry 403, which
        // GrokReplies names a dead credential behind another status, is what makes a freshness judgement meaningful
        // here. A refused login (401) is the server contradicting the file, and a revoked token can be refused while
        // the file still reads hours out — vetoing that refresh would serve a dead token and REGRESS, not protect.
        if (!deadCredential) return true
        // The DECLARED expiry — the file's own `expires` — never a synthesized ceiling. parseSnapshot
        // rethrows anything that is not proven absence, so the guard is the caller's here just as it
        // is inside readSnapshot; an unreadable file yields null and vetoes nothing.
        val declaredExpiryMs = Cancellables.runCatchingCancellable { authJson.parseSnapshot() }
            .onFailure {
                log(
                    "[$LOG_TAG] auth.json unreadable while judging a dead credential; no veto, refresh runs: " +
                        SafeFailureText.render(it),
                )
            }
            .getOrNull()?.expiresAtMs
        val demonstrablyFresh =
            declaredExpiryMs != null && declaredExpiryMs - clock() >= PROACTIVE_WINDOW_MS
        if (demonstrablyFresh) {
            log(
                "[$LOG_TAG] upstream named this credential dead while it is valid for another " +
                    "${(declaredExpiryMs - clock()) / MS_PER_S}s. That is not an expiry, so NO refresh and " +
                    "no sign-in. The answer's own words are in this turn's failure.",
            )
        }
        return !demonstrablyFresh
    }
}
