// NEW: V4-100 (arch-audit 2026-09-17) — an HTTP status code the gateway names is declared ONCE.
//
// SCAR. 429 had SIX spellings across six files (RATE_LIMITED, RATE_LIMIT_STATUS, HTTP_TOO_MANY,
// RATE_LIMITED_STATUS, STATUS_RATE_LIMIT) and 529 had three (OVERLOADED_STATUS,
// GATEWAY_CAPACITY_STATUS, STATUS_OVERLOADED), with nothing keeping any of them equal. The
// classifier's "overloaded" and the admission responder's "capacity" could disagree about the same
// number forever while every unit test stayed green — the drift is invisible because each file
// only ever reads its own copy. The census is wider than the two notorious codes: fifty status
// declarations across sixteen files, all of them a private re-derivation of a number the protocol
// already fixes.
//
// WHY AN OBJECT AND NOT A LIST OF TOP-LEVEL CONSTS: `kt-no-top-level-functions` is not the reason
// (it bans functions, not properties) — the reason is the name. `HttpStatus.TOO_MANY_REQUESTS`
// cannot collide with a file-local `TOO_MANY_REQUESTS`, so a reader grepping the bare number gets
// exactly one hit, and the reference carries where the number's authority comes from.
//
// WHY CORE: the same constraint ErrorEnvelope.of records one file over. The sites are spread over
// :upstream, :provider-grok, :dialect-openai-responses, :daemon-head, :daemon-control and :app, and
// :upstream cannot import :daemon-head — so any module below the top of that fan-in is the only
// place all of them can reach. Core is the floor they share.
//
// WHAT IS DELIBERATELY NOT HERE. 200/202 (HTTP_OK, HTTP_ACCEPTED, OK_STATUS) are left as they are:
// the wall's subject is a status DECLARATION that had drifted into copies, and 200 was never
// flagged or duplicated across modules — converting it would perturb a baselined COPY group for
// no correctness gain. The same goes for the non-status magnitudes that merely look like statuses
// (499/599 range ends, 501, 202): they are not codes any site answers with, so they stay local
// until something makes them a second site.
//
// WALL: quality/rules/kotlin/kt-http-status-single-source.yml names THIS path in its `ignores:` —
// the sole allowed declaration site, exempt BY PATH, so no other file can claim the exemption.
package splice.core.wire

/** Every HTTP status code splice names, in one place. A member here is a WIRE fact and nothing
 *  more: what a status MEANS for retry or refusal stays with the module that decides it. */
public object HttpStatus {

    /** The client sent something the head cannot parse, or a model it does not proxy. */
    public const val BAD_REQUEST: Int = 400

    /** No credential, or one the upstream rejected outright; always worth a token refresh. */
    public const val UNAUTHORIZED: Int = 401

    /** Payment Required. deepseek answers a spent balance with it, so it reads as quota exhaustion. */
    public const val PAYMENT_REQUIRED: Int = 402

    /** Forbidden; plan/permission refusals share the code, so the BODY decides whether it is auth. */
    public const val FORBIDDEN: Int = 403

    /** The path or resource does not exist upstream; never retried, the path will not appear. */
    public const val NOT_FOUND: Int = 404

    /** Request Timeout; the one 4xx besides 429 that the retry matrix treats as transient. */
    public const val REQUEST_TIMEOUT: Int = 408

    /** Content Too Large; the admission plane's answer when a request body beats the byte cap. */
    public const val CONTENT_TOO_LARGE: Int = 413

    /** Unprocessable Entity; the top of the rejection window an upstream may answer a bad turn with. */
    public const val UNPROCESSABLE_ENTITY: Int = 422

    /** Rate Limited; the code that arms the shared cooldown and the one clients retry on. */
    public const val TOO_MANY_REQUESTS: Int = 429

    /** The floor of the server-error window, and the code a bare internal failure is reported as. */
    public const val INTERNAL_SERVER_ERROR: Int = 500

    /** Bad Gateway; splice's own non-stream terminal status for an upstream error it could not name. */
    public const val BAD_GATEWAY: Int = 502

    /** Service Unavailable; a transient server condition, retried under the same budget as 5xx. */
    public const val SERVICE_UNAVAILABLE: Int = 503

    /** Gateway Timeout; transient like the other 5xx and retried under the same budget. */
    public const val GATEWAY_TIMEOUT: Int = 504

    /** The upstream's overload status; splice re-emits it as its own "Gateway At Capacity" terminal. */
    public const val OVERLOADED: Int = 529
}
