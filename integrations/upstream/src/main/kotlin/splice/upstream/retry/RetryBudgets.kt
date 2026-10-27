// NEW: failure-handling slice 4. Every budget the retry engine spends, each with its reason (final-design.md section 8).
// The schedules are spelled as a first wait, its doubling and a ceiling, so a wait anywhere in a schedule is one of
// these numbers and RetryEngine holds no number of its own.
package splice.upstream.retry

/** A stated lift this close is waited out on the login, never held: Claude Code abandons and re-sends a request it
 *  waits on for half a minute or more, and a hold costs a committed stream. */
internal const val HOLD_FLOOR_MS: Long = 15_000L

/** The longest a stated reset is held for: a reset further away is past any session's use, so the turn ends. */
internal const val MAX_HOLD_MS: Long = 604_800_000L

/** A wait this long or longer is told when it starts; a shorter one is waited in silence (section 8, "Lines from
 *  waits"). */
internal const val VISIBLE_WAIT_MS: Long = 10_000L

/** A burst limit with no time stated, or one stated at 15 s or less: waited 2, 4, 8 and 15 s, then held. Four waits
 *  cover the 30 s window a provider's burst counter keeps. */
internal const val BURST_WAITS: Int = 4
internal const val BURST_FIRST_WAIT_MS: Long = 2_000L // why: the first of the 2, 4, 8 and 15 s burst waits
internal const val BURST_WAIT_CAP_MS: Long = 15_000L // why: a stated lift at 15 s or less is still waited

/** An overload with no time stated: waited 2, 4, 8, 15 and 30 s, about a minute, then the turn ends R. */
internal const val OVERLOAD_WAITS: Int = 5
internal const val OVERLOAD_FIRST_WAIT_MS: Long = 2_000L // why: the first of the 2, 4, 8, 15 and 30 s overload waits
internal const val OVERLOAD_WAIT_CAP_MS: Long = 15_000L // why: the doubling stops here before the last wait
internal const val OVERLOAD_LAST_WAIT_MS: Long = 30_000L // why: the final wait brings the schedule to about a minute

/** A model still loading takes minutes: after the overload waits it is asked every 60 s, up to 5 minutes in all. */
internal const val LOADING_WAIT_MS: Long = 60_000L
internal const val LOADING_PATIENCE_MS: Long = 300_000L // why: a loading model is asked for up to 5 minutes in all

/** A path that does not answer: waited 1, 2, 4, 8, 16, 30, 30 and 30 s, about 2 minutes, which outlasts a local model
 *  server's restart (V4-164: one came back after 43 s). */
internal const val UNREACHABLE_WAITS: Int = 8
internal const val UNREACHABLE_FIRST_WAIT_MS: Long = 1_000L // why: the first wait of about 2 minutes in all
internal const val UNREACHABLE_WAIT_CAP_MS: Long = 30_000L // why: the doubling holds at 30 s, about 2 minutes in all

/** A server error before the provider's stream began: re-sent after 200, 400 and 800 ms. */
internal const val SERVER_RESENDS: Int = 3
internal const val SERVER_FIRST_RESEND_MS: Long = 200L // why: the first of the 200, 400 and 800 ms resends

/** Recoveries of an answer whose stream began, shared by a server error, a tear and a silence mid-answer and by a
 *  failed fold round: each continues from what arrived. The failed and truncated rows make 6 requests with it. */
internal const val MAX_STREAM_RECOVERIES: Int = 5

/** A stream that closed before any event, or a first byte that never came, is sent fresh twice. */
internal const val MAX_FRESH_REISSUES: Int = 2

/** An empty answer is asked again once. */
internal const val MAX_EMPTY_RETRIES: Int = 1

/** Stale reasoning is left out of the body once; refused again, the turn ends. */
internal const val MAX_AMENDS: Int = 1

/** A login splice holds is renewed once per turn, by up to 3 token exchanges 2 s and 4 s apart: a token endpoint's
 *  429 or 5xx passes in seconds, and its refusal (401, 403, invalid_grant) ends the renewal at once. */
internal const val MAX_REFRESHES_PER_LOGIN: Int = 1
internal const val MAX_TOKEN_EXCHANGES: Int = 3 // why: a token endpoint 429 or 5xx passes in seconds
internal const val EXCHANGE_FIRST_WAIT_MS: Long = 2_000L // why: token exchanges are tried 2 s and 4 s apart

/** A failure no reader's table names is tried once more, then ends by whose error it is. */
internal const val MAX_UNRECOGNIZED_RETRIES: Int = 1

/** A code-mode runtime that did not start is started once more. */
internal const val MAX_CODE_MODE_STARTS: Int = 1

/** A codex round cut at its reasoning bound is continued 3 times, then its answer stands, as the fold did at its cap. */
internal const val MAX_FOLD_CONTINUES: Int = 3

/** Every turn ends within this many sends outside holds, whatever its failures: the sum of the largest budgets a
 *  single cause can spend, with room for a refresh and a login switch. */
internal const val MAX_ATTEMPTS_OUTSIDE_HOLDS: Int = 24

/** How long the ResendLedger remembers a request that ended R: past an hour a re-send is the person's own. */
internal const val RESEND_TTL_MS: Long = 3_600_000L
