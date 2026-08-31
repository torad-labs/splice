// PORT-OF: server/src/codex/errors.mjs @ pre-public-port-baseline — invariants: ONE classifier for BOTH
// transports (v29 had two and SSE overflows became raw api_error, so Claude Code hard-errored
// instead of compacting); REGEX ORDER IS THE SPEC — overflow FIRST (v29 tested auth first and
// "too many tokens" classified as authentication_error), auth regex never matches the bare
// word "token"; overflow rewrites to carry "prompt is too long" (Claude Code's compact
// trigger phrasing); 502 -> 529 so Claude Code retries as overloaded.
package splice.spi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.core.turn.ErrorType
import splice.core.util.Cancellables
import splice.core.util.JsonScalars

// ClassifiedFailure + FailureSource live in FailureKinds.kt (concentration, 2026-08-19).

public object UpstreamFailureClassifier {
    private val overflowRe = Regex(
        "prompt is too long|context.{0,40}window|maximum context|too many tokens|" +
            "token limit|context_length|exceeds? (?:the )?context",
        RegexOption.IGNORE_CASE,
    )
    private val rateRe = Regex("rate.?limit|quota|\\b429\\b", RegexOption.IGNORE_CASE)
    private val authRe = Regex(
        "\\bauth\\w*\\b|unauthorized|token (?:expired|invalid|revoked)|invalid[_ ]token",
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
    // MAX_MESSAGE — a 120-char cap let long clauses outrun their own negation.
    private val negatedTryAgainRe = Regex(
        "\\b(?:do\\s+not|don['’]t|never|cannot|can['’]t|should\\s+not|must\\s+not|won['’]t|will\\s+not)" +
            "[^.,;:!?—–\\n\\r]{0,2000}?\\b(?:try\\s+again|retry)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val promptTooLongRe = Regex("prompt is too long", RegexOption.IGNORE_CASE)
    private val lenient = Json { ignoreUnknownKeys = true }

    private const val MAX_MESSAGE = 2000

    /** [code] is the STRUCTURED error code when the caller has one (an SSE `response.failed`
     *  envelope's `error.code`/`error.type`), kept separate from the display text so provenance
     *  survives into the transience decision (DR-10 redo: flattening it into the text let free
     *  wording overrule a deterministic code). HTTP callers omit it — the body parse extracts
     *  its own. */
    public fun classify(
        source: FailureSource,
        text: String?,
        status: Int? = null,
        code: String? = null,
    ): ClassifiedFailure {
        val raw = text.orEmpty()
        val extracted = if (source == FailureSource.HTTP) {
            extractHttpError(raw, status)
        } else {
            ExtractResult.Fields(raw, code.orEmpty())
        }
        return when (extracted) {
            is ExtractResult.Gateway -> extracted.failure
            is ExtractResult.Fields -> classifyContent(extracted, status)
        }
    }

    // body parse is best-effort by design: a malformed/HTML body keeps the raw text (and, when it
    // looks like a gateway page, short-circuits to a gateway error) rather than crashing classify.
    private fun extractHttpError(raw: String, status: Int?): ExtractResult {
        var message = raw
        var code = ""
        val parsed = Cancellables.runCatchingCancellable {
            val j = lenient.parseToJsonElement(raw).jsonObject
            val err = j["error"]?.jsonObject
            message = JsonScalars.str(err, "message")
                ?: JsonScalars.str(j, "message")
                ?: raw
            code = JsonScalars.str(err, "type")
                ?: JsonScalars.str(err, "code")
                ?: ""
            // Keep the fields that tell the operator WHAT TO DO. The bare `message` alone reads
            // "The usage limit has been reached" with no hint the ChatGPT Pro quota is six days
            // out (2026-07-26: the reset was 142h away and nothing on the wire said so).
            message += quotaSuffix(err)
        }
        if (parsed.isFailure && gatewayHtmlRe.containsMatchIn(message)) {
            val type = if (status == BAD_GATEWAY) ErrorType.OVERLOADED else ErrorType.API_ERROR
            return ExtractResult.Gateway(
                ClassifiedFailure(
                    type,
                    "ChatGPT backend $status (gateway)",
                    transient = status != null && status >= SERVER_ERROR_FLOOR,
                ),
            )
        }
        return ExtractResult.Fields(message, code)
    }

    // the ordered cascade IS the ported contract — overflow, then rate, then auth, then status floors.
    private fun classifyContent(fields: ExtractResult.Fields, status: Int?): ClassifiedFailure {
        val msg = fields.message
        val blob = "${fields.code} ${fields.message}"
        return when {
            overflowRe.containsMatchIn(blob) -> overflowFailure(msg)
            status == RATE_LIMIT_STATUS || rateRe.containsMatchIn(blob) ->
                ClassifiedFailure(ErrorType.RATE_LIMIT, msg.take(MAX_MESSAGE))
            status == AUTH_STATUS || authRe.containsMatchIn(blob) ->
                ClassifiedFailure(ErrorType.AUTHENTICATION, msg.take(MAX_MESSAGE))
            status == BAD_GATEWAY ->
                ClassifiedFailure(ErrorType.OVERLOADED, msg.take(MAX_MESSAGE), transient = true)
            else -> statusFallback(status, msg, fields.code)
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

    public fun overflowFailure(msg: String): ClassifiedFailure {
        val message = if (promptTooLongRe.containsMatchIn(msg)) msg else "prompt is too long: $msg"
        return ClassifiedFailure(ErrorType.INVALID_REQUEST, message.take(MAX_MESSAGE))
    }

    private fun isStatuslessTransient(message: String): Boolean =
        transientConditionRe.containsMatchIn(message) ||
            (tryAgainRe.containsMatchIn(message) && !negatedTryAgainRe.containsMatchIn(message))

    /** DR-10 redo (codex): provenance beats wording. When the vendor named a structured code, the
     *  EXACT retryable allowlist decides — free text can never overrule it, so a deterministic
     *  `invalid_parameter` whose message happens to say "unavailable" or "try again" is never
     *  re-POSTed. Text heuristics apply only when no code arrived. */
    private fun statuslessTransience(code: String, message: String): Boolean = when {
        code.isNotBlank() -> code.lowercase() in RETRYABLE_CODES
        else -> isStatuslessTransient(message)
    }

    private fun statusFallback(status: Int?, msg: String, code: String): ClassifiedFailure = when {
        status != null && status >= SERVER_ERROR_FLOOR ->
            ClassifiedFailure(ErrorType.API_ERROR, msg.take(MAX_MESSAGE), transient = true)
        status != null && status >= CLIENT_ERROR_FLOOR ->
            ClassifiedFailure(ErrorType.INVALID_REQUEST, msg.take(MAX_MESSAGE))
        else -> ClassifiedFailure(
            ErrorType.API_ERROR,
            msg.take(MAX_MESSAGE),
            transient = statuslessTransience(code, msg),
        )
    }

    /** 502 from the ChatGPT gateway is transient — surface as 529 so Claude Code retries. */
    public fun mapOutStatus(status: Int): Int = if (status == BAD_GATEWAY) OVERLOADED_STATUS else status

    private sealed class ExtractResult {
        data class Fields(val message: String, val code: String) : ExtractResult()
        data class Gateway(val failure: ClassifiedFailure) : ExtractResult()
    }

    private const val MS_PER_S = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86_400L

    // The exact codes a status-less failure may be re-POSTed on: named transient server
    // conditions only. Anything else — known-deterministic or unknown — does not earn a
    // full-context replay on the say-so of its message text.
    private val RETRYABLE_CODES = setOf(
        "server_error",
        "internal_error",
        "internal_server_error",
        "overloaded",
        "overloaded_error",
        "timeout",
        "request_timeout",
        "service_unavailable",
        "temporarily_unavailable",
        // DR-71: the engine/model-scoped overload spellings vendors emit are the same named
        // transient server condition — a deterministic-looking absence here made them non-retryable.
        "engine_overloaded",
        "model_overloaded",
    )

    private const val RATE_LIMIT_STATUS = 429
    private const val AUTH_STATUS = 401
    private const val SERVER_ERROR_FLOOR = 500
    private const val CLIENT_ERROR_FLOOR = 400
    private const val BAD_GATEWAY = 502
    private const val OVERLOADED_STATUS = 529
}
