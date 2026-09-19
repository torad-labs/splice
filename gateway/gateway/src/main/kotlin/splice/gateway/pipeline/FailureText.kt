// NEW: V4-59 — the operator's transcript was receiving raw vendor JSON. TurnPipeline used to hand
// outcome.message straight to the wire — the very string upstream sent — so a provider's error body
// landed in his chat as literal braces. And splice did it to ITSELF: RateLimitCooldown's fail-fast
// throws a hand-built {"detail":...}, so our own careful sentence arrived wrapped in JSON we wrote.
// UpstreamFailureClassifier decides the failure's TYPE; nothing decided its WORDS.
//
// This is the one place a failure becomes client-facing text. It answers the two questions that had
// no answer before: WHICH failure is this — a stable code, so a future report can be keyed on a
// token rather than on a sentence the operator retyped from his screen — and WHAT should a human
// read, which is a sentence and never a payload.
//
// THE CODE IS DERIVED, NEVER ROSTERED. It is "SPLICE-" plus the ErrorType name with underscores as
// hyphens, so a new ErrorType cannot arrive without a code and the taxonomy and the namespace cannot
// drift apart. That is V4-56's denominator discipline applied to prose: a hand-written code table
// would be a second list that could disagree with the enum in silence. The SPLICE- prefix is what
// keeps the token from being read as provider text or as some client's own marker once it is pasted
// into a report.
//
// WHAT A BODY CONTRIBUTES. A JSON object contributes exactly ONE human-meaningful field — never its
// braces, and never a substring of its text (a truncated dump is still a dump). A body that cannot
// be read at all is DESCRIBED, because an unreadable payload's raw text is precisely the thing a
// reader cannot use. A body that is not JSON is already prose and rides through untouched.
package splice.gateway.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.ErrorType
import splice.core.util.Cancellables
import splice.core.util.ERR_SNIPPET

/** A failure as the client should read it: a stable, greppable [code] and a human [body]. */
internal data class FailureText(val code: String, val body: String)

/** The single presentation seam: (ErrorType, raw upstream message) -> what a human reads. */
internal class FailurePresenter {

    /** The pair a caller needs, kept separate from the rendering so a test can pin the code and the
     *  sentence independently instead of matching one composed string. */
    fun present(type: ErrorType, message: String): FailureText = FailureText(codeFor(type), sentence(message))

    /** V4-59: the code namespace. Derived from the enum on purpose — see this file's header. */
    fun codeFor(type: ErrorType): String = CODE_PREFIX + type.name.replace('_', '-')

    /** The single line both endings render, so a code cannot be spelled one way in a text block and
     *  another in an error event — the two would then be ungreppable as one class of failure. */
    fun spoken(type: ErrorType, message: String): String {
        val text = present(type, message)
        return "[${text.code}] ${text.body}"
    }

    /** The human sentence for a failure body. See the header for what each shape contributes. */
    fun sentence(message: String): String {
        // A body that is not JSON is prose and rides through untouched: null IS the complete story
        // here, by design (see the header). Cancellable so a cancelled turn actually stops.
        // ast-grep-ignore: kt-no-silent-result-collapse -- non-JSON body is prose by contract, see header
        val element = Cancellables.runCatchingCancellable { Json.parseToJsonElement(message) }.getOrNull()
        return when {
            // Not JSON rides through untouched. A rule that ALSO rejected markup was tried here and
            // reverted: zero_event_auth's body is an <html> page whose text reads "401 Unauthorized:
            // your session token has expired, please sign in again", and the login-hint test pins
            // that the operator keeps it. Markup is not the same as noise, and a shape test cannot
            // tell a proxy's 502 page from a vendor's prose, so this layer does not guess.
            element == null -> message.take(ERR_SNIPPET).ifBlank { UNREADABLE }
            // A JSON string is prose wearing quotes — unwrap it rather than describing it.
            element is JsonPrimitive && element.isString -> element.content.ifBlank { UNREADABLE }
            element is JsonObject -> humanField(element) ?: UNREADABLE
            // Valid JSON that is neither an object nor a string (an array, a number, a bare true):
            // there is no human-meaningful field to lift out of it, so it is described, not dumped.
            else -> UNREADABLE
        }
    }

    /** The one field worth showing: our own fail-fast and the OpenAI-shaped bodies use `detail`, an
     *  Anthropic-shaped body nests it under `error`, and a bare `message` is the last resort. */
    private fun humanField(obj: JsonObject): String? = firstNonBlank(
        obj[DETAIL],
        (obj[ERROR] as? JsonObject)?.get(MESSAGE),
        obj[MESSAGE],
    )

    private fun firstNonBlank(vararg candidates: Any?): String? {
        val found = candidates.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString) }
        return found?.content?.takeIf { it.isNotBlank() }
    }
}

private const val CODE_PREFIX = "SPLICE-"

private const val DETAIL = "detail"
private const val ERROR = "error"
private const val MESSAGE = "message"

/** Said instead of a payload nobody can read. Its job is to be TRUE and short: the operator learns
 *  the upstream failed in a way we could not parse, which is itself the diagnostic, rather than
 *  receiving the unparseable thing he cannot act on. */
private const val UNREADABLE = "the upstream returned an error that could not be read"
