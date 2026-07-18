// PORT-OF: server/src/codex/errors.mjs @ 4ca99f7 — invariants: ONE classifier for BOTH
// transports (v29 had two and SSE overflows became raw api_error, so Claude Code hard-errored
// instead of compacting); REGEX ORDER IS THE SPEC — overflow FIRST (v29 tested auth first and
// "too many tokens" classified as authentication_error), auth regex never matches the bare
// word "token"; overflow rewrites to carry "prompt is too long" (Claude Code's compact
// trigger phrasing); 502 -> 529 so Claude Code retries as overloaded.
package splice.spi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.turn.ErrorType
import splice.core.util.runCatchingCancellable

public data class ClassifiedFailure(val type: ErrorType, val message: String)

public enum class FailureSource { HTTP, SSE }

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
    private val promptTooLongRe = Regex("prompt is too long", RegexOption.IGNORE_CASE)
    private val lenient = Json { ignoreUnknownKeys = true }

    private const val MAX_MESSAGE = 2000

    public fun classify(source: FailureSource, text: String?, status: Int? = null): ClassifiedFailure {
        val raw = text.orEmpty()
        val extracted = if (source == FailureSource.HTTP) {
            extractHttpError(raw, status)
        } else {
            ExtractResult.Fields(raw, "")
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
        val parsed = runCatchingCancellable {
            val j = lenient.parseToJsonElement(raw).jsonObject
            val err = j["error"]?.jsonObject
            message = err?.get("message")?.jsonPrimitive?.content
                ?: j["message"]?.jsonPrimitive?.content
                ?: raw
            code = err?.get("type")?.jsonPrimitive?.content
                ?: err?.get("code")?.jsonPrimitive?.content
                ?: ""
        }
        if (parsed.isFailure && gatewayHtmlRe.containsMatchIn(message)) {
            return ExtractResult.Gateway(
                ClassifiedFailure(ErrorType.API_ERROR, "ChatGPT backend $status (gateway)"),
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
            else -> statusFallback(status, msg)
        }
    }

    private fun overflowFailure(msg: String): ClassifiedFailure {
        val message = if (promptTooLongRe.containsMatchIn(msg)) msg else "prompt is too long: $msg"
        return ClassifiedFailure(ErrorType.INVALID_REQUEST, message.take(MAX_MESSAGE))
    }

    private fun statusFallback(status: Int?, msg: String): ClassifiedFailure = when {
        status != null && status >= SERVER_ERROR_FLOOR -> ClassifiedFailure(ErrorType.API_ERROR, msg.take(MAX_MESSAGE))
        status != null && status >= CLIENT_ERROR_FLOOR ->
            ClassifiedFailure(ErrorType.INVALID_REQUEST, msg.take(MAX_MESSAGE))
        else -> ClassifiedFailure(ErrorType.API_ERROR, msg.take(MAX_MESSAGE))
    }

    /** 502 from the ChatGPT gateway is transient — surface as 529 so Claude Code retries. */
    public fun mapOutStatus(status: Int): Int = if (status == BAD_GATEWAY) OVERLOADED_STATUS else status

    private sealed class ExtractResult {
        data class Fields(val message: String, val code: String) : ExtractResult()
        data class Gateway(val failure: ClassifiedFailure) : ExtractResult()
    }

    private const val RATE_LIMIT_STATUS = 429
    private const val AUTH_STATUS = 401
    private const val SERVER_ERROR_FLOOR = 500
    private const val CLIENT_ERROR_FLOOR = 400
    private const val BAD_GATEWAY = 502
    private const val OVERLOADED_STATUS = 529
}
