// NEW: what a 403 on a grok credential means: an expiry the refresh fixes, or a wall no refresh or sign-in can.
// Split from GrokAuthProvider.kt, which answers the transport's two questions by asking this.
package splice.provider.grok

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.upstream.retry.MS_PER_S

// The one status whose auth meaning xAI overloads (expiry AND billing), so the one status a
// freshness judgement can arbitrate, is HttpStatus.FORBIDDEN — V4-135 reads it from the single
// declaration site instead of a local copy. See allowRefreshAfterFailure.
internal class GrokForbidden(
    private val authJson: GrokAuthJson,
    private val clock: WallClock,
    private val log: LogSink,
) {
    /** RULE 3's sentence builder. Stateless, so one instance is enough (see GrokOAuth's file-scope
     *  Json comment for why the parser it shares is file scope rather than per-instance). */
    private val oauth = GrokOAuth()

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
     * RULE 3 appears here only as a SENTENCE: a recognised entitlement body is logged with its cause
     * and the vendor's own top-up link. Those strings never reach the decision below.
     */
    fun allowRefreshAfterFailure(status: Int, body: String): Boolean {
        // 403 ONLY. xAI reports an expired token as 403, which is what makes a freshness judgement
        // meaningful here; a 401 is the server contradicting the file, and a revoked token can 401
        // while the file still reads hours out — vetoing that refresh would serve a dead token and
        // REGRESS, not protect. The only statuses reaching this call are 401 and 403 (the transport
        // consults the veto solely for `isAuthRefreshableFailure`), so this is the whole surface.
        if (status != HttpStatus.FORBIDDEN) return true
        // The DECLARED expiry — the file's own `expires` — never a synthesized ceiling. parseSnapshot
        // rethrows anything that is not proven absence, so the guard is the caller's here just as it
        // is inside readSnapshot; an unreadable file yields null and vetoes nothing.
        val declaredExpiryMs = Cancellables.runCatchingCancellable { authJson.parseSnapshot() }
            .onFailure {
                log(
                    "[$LOG_TAG] auth.json unreadable while judging a $status; no veto, refresh runs: " +
                        SafeFailureText.render(it),
                )
            }
            .getOrNull()?.expiresAtMs
        val demonstrablyFresh =
            declaredExpiryMs != null && declaredExpiryMs - clock() >= PROACTIVE_WINDOW_MS
        if (demonstrablyFresh) {
            val sentence = oauth.entitlementSentence(body)
                ?: "body not recognised as an entitlement rejection"
            log(
                "[$LOG_TAG] upstream $status on a credential valid for another " +
                    "${(declaredExpiryMs - clock()) / MS_PER_S}s. That is not an expiry, so NO refresh and " +
                    "no sign-in. $sentence",
            )
        }
        return !demonstrablyFresh
    }

    /**
     * V4-73: grok's spent account is a 403 whose body names the billing wall
     * (`personal-team-blocked:spending-limit`), and until this override existed the transport
     * classified it as a terminal invalid_request — bypassing the cooldown, the account pool and
     * every client retry, when the operator's law is that credits exhaustion is retried until the
     * credits are back. The phrase list stays HERE, in the vendor module (SEPARATION); the port is
     * the neutral question and this is the only place that answers it with vendor spelling.
     *
     *  403 ONLY, and only when [GrokOAuth.isEntitlementRejection] recognises the body — the same
     *  list V4-38 uses for the refresh veto. An UNRECOGNISED 403 keeps today's behaviour exactly,
     *  including the freshness rule above: this rewrite never widens what counts as a quota wall.
     */
    fun isQuotaExhausted(status: Int, body: String): Boolean =
        status == HttpStatus.FORBIDDEN && oauth.isEntitlementRejection(body)
}
