// PORT-OF: server/src/codex/errors.mjs @ pre-public-port-baseline — invariants: ONE classifier for BOTH
// transports (v29 had two and SSE overflows became raw api_error, so Claude Code hard-errored
// instead of compacting); REGEX ORDER IS THE SPEC — overflow FIRST (v29 tested auth first and
// "too many tokens" classified as authentication_error), auth regex never matches the bare
// word "token"; overflow rewrites to carry "prompt is too long" (Claude Code's compact
// trigger phrasing); 502 -> 529 so Claude Code retries as overloaded.
package splice.upstream.failure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LoginOwner
import splice.core.failure.ProviderWords
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.failure.ServerFault
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.upstream.local.LlamaCppErrors
import splice.upstream.retry.MS_PER_S

// ClassifiedFailure + FailureSource live in FailureKinds.kt (concentration, 2026-08-19).

public object UpstreamFailureClassifier {
    private val overflowRe = Regex(
        "prompt is too long|context.{0,40}window|maximum context|too many tokens|" +
            "token limit|context_length|exceeds? (?:the )?context",
        RegexOption.IGNORE_CASE,
    )
    private val rateRe = Regex("rate.?limit|quota|\\b429\\b", RegexOption.IGNORE_CASE)

    // DR-72 (soak, 2x live): the bare \bauth\w*\b prefix matched "authorized" inside ChatGPT's
    // cyber_policy invitation sentence ("To get authorized for security work, join..."), turning
    // a deterministic content-flag refusal into authentication_error (re-login UX). Auth wording
    // must state an auth FAILURE: authenticate*/unauthorized/not-authorized/bare auth/auth error/
    // authorization/authorisation (the header word, suffix-open — DR-83: underscore is a word
    // character, so a closed \bauthorization\b dropped every snake_case authorization_* code the
    // vendor error.code field emits by construction) plus authz — never the standalone positive
    // "authorized" ("authoriz" takes the -ed suffix, never -ation, so the suffix-open branch
    // cannot reach it).
    private val authRe = Regex(
        "\\bauthenticat\\w*\\b|\\bunauthori[sz]\\w*\\b|\\bnot authori[sz]ed\\b|\\bauth\\b|" +
            "\\bauth[_ ]error\\b|\\bauthori[sz]ation\\w*\\b|\\bauthz\\w*\\b|" +
            "token (?:expired|invalid|revoked)|invalid[_ ]token",
        RegexOption.IGNORE_CASE,
    )
    private val gatewayHtmlRe = Regex("<html|bad gateway|cloudflare", RegexOption.IGNORE_CASE)

    // Status-less SSE failures are re-POSTable only when the vendor text explicitly names a
    // transient server condition. Default false: policy/parameter/unknown failures can be
    // deterministic, and replaying the full context cannot change them. Bare "unavailable" was
    // dropped (DR-10 redo, codex counterexample): "The selected model is unavailable in your
    // region" is a deterministic restriction — only a qualified service outage wording counts.
    private val transientConditionRe = Regex(
        "server[_ -]?error|internal[_ -]?error|temporar(?:y|ily)|overload(?:ed)?|" +
            "service[_ -]?(?:is[_ -])?unavailable|currently[_ -]?unavailable|timed? ?out",
        RegexOption.IGNORE_CASE,
    )

    // DR-71 (codex adjudication probe, 2026-08-31): "retry" is the invitation synonym vendors
    // actually use ("Please retry") — \b keeps retried/retries/retrying out.
    private val tryAgainRe = Regex("\\b(?:try\\s+again|retry)\\b", RegexOption.IGNORE_CASE)

    // DR-45 redo: the old `(?:\w+\s+){0,2}` window missed long-form negation ("do not attempt to
    // resubmit this request or try again"). The negator now suppresses across its WHOLE clause —
    // anything up to a clause boundary — so only a "try again" in a separate sentence/clause
    // ("Do not panic. Please try again.") still reads as an invitation to retry. DR-71: the
    // boundary set includes em/en dash and line breaks (a dash-cut fresh clause is a real
    // invitation), the negators include won't/will not, and the clause budget is the full
    // MAX_MESSAGE — a 120-char cap let long clauses outrun their own negation. The em dash is spelled
    // as the regex escape \x{2014}: it is a boundary this regex reads in vendor prose, not text
    // splice writes (quality/rules/kotlin/kt-no-emdash-cli-text.yml).
    private val negatedTryAgainRe = Regex(
        "\\b(?:do\\s+not|don['’]t|never|cannot|can['’]t|should\\s+not|must\\s+not|won['’]t|will\\s+not)" +
            "[^.,;:!?\\x{2014}–\\n\\r]{0,2000}?\\b(?:try\\s+again|retry)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val promptTooLongRe = Regex("prompt is too long", RegexOption.IGNORE_CASE)
    private val lenient = Json { ignoreUnknownKeys = true }

    private const val MAX_MESSAGE = 2000

    /** [code] is the STRUCTURED error code when the caller has one (an SSE `response.failed`
     *  envelope's `error.code`/`error.type`), kept separate from the display text so provenance
     *  survives into the transience decision (DR-10 redo: flattening it into the text let free
     *  wording overrule a deterministic code). HTTP callers omit it — the body parse extracts
     *  its own. [said] is what the provider said that a person may read, which every cause carries: by default its
     *  own message field in [text] ([saidIn]); a caller that rewrote [text] into splice's words hands the provider's
     *  own instead, or null. */
    public fun classify(
        source: FailureSource,
        text: String?,
        status: Int? = null,
        code: String? = null,
        said: ProviderWords? = saidIn(source, text),
    ): ClassifiedFailure {
        val raw = text.orEmpty()
        val extracted = if (source == FailureSource.HTTP) {
            extractHttpError(raw, status)
        } else {
            // V4-164: an in-band local-runtime failure is named the same way its HTTP twin is.
            ExtractResult.Fields(LlamaCppErrors.explain(null, code.orEmpty(), raw), code.orEmpty())
        }
        // V4-117: stamped at the ONE exit rather than at each of the ten construction sites below,
        // because every verdict leaves through here and the status is this function's own argument.
        // A per-site stamp would be ten places to forget it. The Gateway short-circuit is stamped
        // too: the status is still the upstream's, and all that failed was the body's parsing.
        return when (extracted) {
            is ExtractResult.Gateway -> extracted.failure
            is ExtractResult.Fields -> classifyContent(extracted, status, said)
        }.copy(status = status)
    }

    // body parse is best-effort by design: a malformed/HTML body keeps the raw text (and, when it
    // looks like a gateway page, short-circuits to a gateway error) rather than crashing classify.
    private fun extractHttpError(raw: String, status: Int?): ExtractResult {
        var message = raw
        var code = ""
        val parsed = Cancellables.runCatchingCancellable {
            val j = lenient.parseToJsonElement(raw).jsonObject
            val err = j["error"]?.jsonObject
            message = JsonScalars.str(err, MESSAGE_FIELD)
                ?: JsonScalars.str(j, MESSAGE_FIELD)
                ?: raw
            // A STRING code is the vendor's verdict (cyber_policy, server_is_overloaded) and outranks the category
            // in `type`, as the in-stream readers take it: read type-first, a cyber_policy 400 under an
            // invalid_request type missed the policy rule and was re-sent on the generic curve. A NUMERIC code is
            // llama-server's status, never a kind, so its `type` still names it.
            code = listOfNotNull(
                JsonScalars.strIfString(err?.get("code")).takeIf(String::isNotBlank),
                JsonScalars.str(err, "type"),
                JsonScalars.str(err, "code"),
            ).firstOrNull().orEmpty()
            // Keep the fields that tell the operator WHAT TO DO. The bare `message` alone reads
            // "The usage limit has been reached" with no hint the ChatGPT Pro quota is six days
            // out (2026-07-26: the reset was 142h away and nothing on the wire said so).
            message += quotaSuffix(err)
            // V4-164: llama-server's shapes, named — and its context overflow rewritten into the
            // "prompt is too long" line the overflow rule below (and Claude Code) both key on.
            message = LlamaCppErrors.explain(err, code, message)
        }
        if (parsed.isFailure && gatewayHtmlRe.containsMatchIn(message)) {
            return ExtractResult.Gateway(ClassifiedFailure("upstream $status (gateway)", gatewayCause(status)))
        }
        return ExtractResult.Fields(message, code)
    }

    /** The cause for an HTML body that is not a vendor envelope at all: a 502 is the gateway's own overload, any
     *  other 5xx the upstream failing BEHIND the middlebox, and anything else an in-band report we cannot
     *  attribute further, which a re-send repeats. Its own function because inlining the choice pushed
     *  [extractHttpError] one branch past detekt's cyclomatic ceiling — the decision belongs here anyway, since it
     *  is a rule about status classes rather than a step of parsing. */
    private fun gatewayCause(status: Int?): FailureCause.Provider = when {
        status == HttpStatus.BAD_GATEWAY -> FailureCause.Provider.Overloaded(Lift.Unstated, loading = false)
        status != null && status >= HttpStatus.INTERNAL_SERVER_ERROR -> statusFloor(status)
        else -> FailureCause.Provider.Unrecognized(clientError = true)
    }

    /** What an error status names when nothing sharper did: a 5xx is the server's own failure (a 504 its gateway's
     *  timeout), a 403 the provider's refusal of this caller, and any other 4xx its verdict on the request. */
    private fun statusFloor(status: Int, said: ProviderWords? = null): FailureCause.Provider = when {
        status == HttpStatus.GATEWAY_TIMEOUT -> FailureCause.Provider.ServerError(ServerFault.GATEWAY_TIMEOUT, said)
        status >= HttpStatus.INTERNAL_SERVER_ERROR -> FailureCause.Provider.ServerError(ServerFault.INTERNAL, said)
        status == HttpStatus.FORBIDDEN -> FailureCause.Provider.Forbidden(said)
        else -> FailureCause.Provider.Rejected(staleReasoning = false, said)
    }

    /** What the provider said in [text] that a person may read: the message field of an HTTP body (an error object's
     *  message, an error that is itself a string, or a top-level message or detail), the body itself when it is no
     *  JSON, and an in-band failure's own text, each through [ProviderWordsFilter]. Never the sentences this
     *  classifier adds for the log (the quota suffix, llama-server's explanation, the gateway restatement). */
    public fun saidIn(source: FailureSource, text: String?): ProviderWords? =
        ProviderWordsFilter.of(if (source == FailureSource.HTTP) ownMessage(text.orEmpty()) else text)

    /** The provider's own message field in an HTTP body [raw], or [raw] itself when it is not a JSON object, which
     *  the filter keeps only when it is no JSON at all. */
    private fun ownMessage(raw: String): String? {
        // a body that is no JSON object is the provider's words as sent, which the filter judges whole
        val body = try {
            lenient.parseToJsonElement(raw) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return raw
        val error = body["error"]
        return JsonScalars.str(error as? JsonObject, MESSAGE_FIELD)
            ?: JsonScalars.strIfString(error).takeIf(String::isNotEmpty)
            ?: JsonScalars.str(body, MESSAGE_FIELD)
            ?: JsonScalars.str(body, "detail")
    }

    // the ordered cascade IS the ported contract — overflow, then rate, then auth, then status floors.
    private fun classifyContent(fields: ExtractResult.Fields, status: Int?, said: ProviderWords?): ClassifiedFailure {
        val msg = fields.message
        val blob = "${fields.code} ${fields.message}"
        return when {
            overflowRe.containsMatchIn(blob) -> overflowFailure(msg, said)
            // Provenance beats status: a content-policy refusal is a fact about the REQUEST, and the
            // vendor itself returns it as HTTP 400 when it arrives pre-stream. Mid-stream it comes
            // status-less, and DR-72's api_error verdict was still RETRYABLE on the client side —
            // Claude Code re-sends an api_error with backoff, so every refusal became a ~30s x N
            // storm of the same 1.3MB transcript (242 turns on 2026-09-01, 42 of them compactions
            // that could never complete). invalid_request_error is terminal to the client, and the
            // vendor's own remedy text ("try rephrasing") rides along untouched.
            fields.code.lowercase() in FailureCodes.POLICY_REFUSAL_CODES ->
                ClassifiedFailure(msg.take(MAX_MESSAGE), FailureCause.Provider.Refused(RefusedBy.POLICY, said))
            status == HttpStatus.TOO_MANY_REQUESTS || rateRe.containsMatchIn(blob) ->
                ClassifiedFailure(msg.take(MAX_MESSAGE), FailureCause.Provider.Limited(Lift.Unstated, null, said))
            status == HttpStatus.UNAUTHORIZED || authRe.containsMatchIn(blob) ->
                ClassifiedFailure(msg.take(MAX_MESSAGE), FailureCause.Provider.AuthRejected(LoginOwner.SPLICE, said))
            // Overload: a 502 from the gateway, or capacity by CODE SHAPE. The ChatGPT backend
            // reports "model at capacity" as HTTP 503 or an in-stream response.failed whose code is
            // server_is_overloaded / slow_down — codex-rs names exactly those two (PR #31058, which
            // turned them from turn-ending into a patient same-turn retry). An exact allowlist keeps
            // losing this race one spelling at a time: DR-71 added engine_/model_overloaded, and on
            // 2026-09-01 20:56 a claudex compaction (830KB upstream body) died as a non-transient
            // api_error on server_is_overloaded — no reissue, no salvage, "compaction failed" in the
            // client. Any code spelling overload IS the named transient server condition, and it
            // surfaces as OVERLOADED (529 / overloaded_error): the type Claude Code retries on.
            fields.isOverload(status) ->
                ClassifiedFailure(
                    msg.take(MAX_MESSAGE),
                    FailureCause.Provider.Overloaded(Lift.Unstated, loading = false, said),
                )
            else -> statusFallback(status, msg, fields.code, said)
        }
    }

    /** Upstream quota errors carry the only fields that answer "what do I do now" — when it
     *  resets, and which plan hit the wall — and keeping just `message` threw them away. Appended
     *  as plain text so every consumer (SSE error frame, non-stream envelope, daemon.log) gains it
     *  without an envelope-shape change. Absent fields append nothing. */
    private fun quotaSuffix(err: JsonObject?): String {
        if (err == null) return ""
        val parts = mutableListOf<String>()
        JsonScalars.str(err, "plan_type")?.takeIf { it.isNotBlank() }?.let { parts += "plan=$it" }
        val resetsInS = err["resets_in_seconds"]?.jsonPrimitive?.longOrNull
            ?: err["resets_at"]?.jsonPrimitive?.longOrNull?.let { it - System.currentTimeMillis() / MS_PER_S }
        resetsInS?.takeIf { it > 0 }?.let { parts += "resets in ${humanizeDuration(it)}" }
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    /** Seconds -> "6d 4h" / "4h 12m" / "45s". Coarse on purpose: the operator needs the ORDER of
     *  magnitude ("six days, switch heads") far more than the exact second. */
    private fun humanizeDuration(totalSeconds: Long): String {
        val d = totalSeconds / SECONDS_PER_DAY
        val h = (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
        val m = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${totalSeconds}s"
        }
    }

    /** An overflow in [msg], restated for the log in the words Claude Code compacts on; [said] is what the provider
     *  said that a person may read, never that restatement. */
    public fun overflowFailure(msg: String, said: ProviderWords? = null): ClassifiedFailure {
        val message = if (promptTooLongRe.containsMatchIn(msg)) msg else "prompt is too long: $msg"
        return ClassifiedFailure(
            message.take(MAX_MESSAGE),
            FailureCause.Provider.TooLong(limitTokens = null, whileAnswering = false, said),
        )
    }

    // DR-71 redo (codex red-repro): the heuristics classify exactly the take(MAX_MESSAGE) view
    // the operator is shown — scanning the untruncated message let an invitation beyond the
    // negation bridge's {0,2000} reach flip a visibly-negated clause to transient, and the bound
    // is what makes that bridge genuinely whole-clause.
    private fun isStatuslessTransient(rawMessage: String): Boolean {
        val message = rawMessage.take(MAX_MESSAGE)
        return transientConditionRe.containsMatchIn(message) ||
            (tryAgainRe.containsMatchIn(message) && !negatedTryAgainRe.containsMatchIn(message))
    }

    /** DR-10 redo (codex): provenance beats wording. When the vendor named a structured code, the
     *  EXACT retryable allowlist decides — free text can never overrule it, so a deterministic
     *  `invalid_parameter` whose message happens to say "unavailable" or "try again" is never
     *  re-POSTed. Text heuristics apply only when no code arrived. */
    private fun statuslessTransience(code: String, message: String): Boolean = when {
        code.isNotBlank() -> code.lowercase() in FailureCodes.RETRYABLE_CODES
        else -> isStatuslessTransient(message)
    }

    private fun statusFallback(
        status: Int?,
        msg: String,
        code: String,
        said: ProviderWords?,
    ): ClassifiedFailure = when {
        status != null && status >= HttpStatus.BAD_REQUEST ->
            ClassifiedFailure(msg.take(MAX_MESSAGE), statusFloor(status, said))
        else -> ClassifiedFailure(
            msg.take(MAX_MESSAGE),
            // No status at all — the in-band case, and the one the row's brief did not name. A re-send can change
            // it only when the vendor's words name a transient condition.
            FailureCause.Provider.Unrecognized(clientError = !statuslessTransience(code, msg), said),
            // A code on neither list is unknown: one re-send, then the honest message. A deterministic code
            // never re-sends, and no code at all is judged by the text heuristics above.
            retryOnce = code.isNotBlank() &&
                code.lowercase().let { it !in FailureCodes.RETRYABLE_CODES && it !in FailureCodes.DETERMINISTIC_CODES },
        )
    }

    private sealed class ExtractResult {
        data class Fields(val message: String, val code: String) : ExtractResult() {
            /** Overload: a 502 from the gateway, or capacity by CODE SHAPE on a status-less (in-stream)
             *  or server-side failure (classifyContent's branch comment carries the provenance). A 4xx
             *  keeps its deterministic verdict whatever its code spells: this arm sits ahead of
             *  statusFallback's client-error branch, so without the floor a permanent 403 spelling
             *  "overload" would be re-POSTed UPSTREAM_RETRIES times (review of #115). Lives on the
             *  fields, not the object: the object sits at detekt's function budget and
             *  classifyContent at its complexity budget. */
            fun isOverload(status: Int?): Boolean = when {
                status == HttpStatus.BAD_GATEWAY -> true
                status == null -> capacityShape()
                else -> status >= HttpStatus.INTERNAL_SERVER_ERROR && capacityShape()
            }

            private fun capacityShape(): Boolean =
                code.lowercase() in FailureCodes.CAPACITY_CODES || FailureCodes.overloadCodeRe.containsMatchIn(code)
        }
        data class Gateway(val failure: ClassifiedFailure) : ExtractResult()
    }

    // The field an error envelope carries its sentence in, read by the classifier and by saidIn alike.
    private const val MESSAGE_FIELD = "message"
    private const val MS_PER_S = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86_400L
}
