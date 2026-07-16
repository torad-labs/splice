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

    @Suppress(
        "ReturnCount",
        "CyclomaticComplexMethod",
        "TooGenericExceptionCaught",
        "InstanceOfCheckForException",
    ) // the ordered cascade IS the ported contract; body parse is best-effort by design
    public fun classify(source: FailureSource, text: String?, status: Int? = null): ClassifiedFailure {
        var msg = text.orEmpty()
        var code = ""
        if (source == FailureSource.HTTP) {
            try {
                val j = lenient.parseToJsonElement(msg).jsonObject
                val err = j["error"]?.jsonObject
                msg = err?.get("message")?.jsonPrimitive?.content
                    ?: j["message"]?.jsonPrimitive?.content
                    ?: msg
                code = err?.get("type")?.jsonPrimitive?.content
                    ?: err?.get("code")?.jsonPrimitive?.content
                    ?: ""
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                if (gatewayHtmlRe.containsMatchIn(msg)) {
                    return ClassifiedFailure(ErrorType.API_ERROR, "ChatGPT backend $status (gateway)")
                }
            }
        }
        val blob = "$code $msg"

        if (overflowRe.containsMatchIn(blob)) {
            val message = if (promptTooLongRe.containsMatchIn(msg)) msg else "prompt is too long: $msg"
            return ClassifiedFailure(ErrorType.INVALID_REQUEST, message.take(MAX_MESSAGE))
        }
        if (status == RATE_LIMIT_STATUS || rateRe.containsMatchIn(blob)) {
            return ClassifiedFailure(ErrorType.RATE_LIMIT, msg.take(MAX_MESSAGE))
        }
        if (status == AUTH_STATUS || authRe.containsMatchIn(blob)) {
            return ClassifiedFailure(ErrorType.AUTHENTICATION, msg.take(MAX_MESSAGE))
        }
        if (status != null && status >= SERVER_ERROR_FLOOR) {
            return ClassifiedFailure(ErrorType.API_ERROR, msg.take(MAX_MESSAGE))
        }
        if (status != null && status >= CLIENT_ERROR_FLOOR) {
            return ClassifiedFailure(ErrorType.INVALID_REQUEST, msg.take(MAX_MESSAGE))
        }
        return ClassifiedFailure(ErrorType.API_ERROR, msg.take(MAX_MESSAGE))
    }

    /** 502 from the ChatGPT gateway is transient — surface as 529 so Claude Code retries. */
    public fun mapOutStatus(status: Int): Int = if (status == BAD_GATEWAY) OVERLOADED_STATUS else status

    private const val RATE_LIMIT_STATUS = 429
    private const val AUTH_STATUS = 401
    private const val SERVER_ERROR_FLOOR = 500
    private const val CLIENT_ERROR_FLOOR = 400
    private const val BAD_GATEWAY = 502
    private const val OVERLOADED_STATUS = 529
}
