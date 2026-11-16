// NEW: LAN share (docs/specs/lan-share.md). How the share listener refuses: in the Anthropic error shape, so Claude Code
// on the other machine prints the message instead of a bare status, with this machine's prefix on every message, so
// the asking machine can tell the server's words from its own. Server mode (docs/specs/server-mode.md) refuses the same
// way under its own prefix, so the shape and the type each status takes are RefusalVoice's, written once. Server mode
// also answers OpenAI-style clients (F4, openai-route), whose SDKs read an error out of OpenAI's shape, so a voice
// speaks one of the two.
package splice.share

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus

/** The shape an error is written in: the one the asking client's SDK reads a message out of. */
public enum class RefusalShape { ANTHROPIC, OPENAI }

/** Errors in one client's [shape], each message under [prefix]. In the Anthropic shape the type follows the status as
 *  Anthropic's own does, and is never `overloaded_error`: a head that can't take a turn is said as that, never as a
 *  busy server worth retrying in silence. In OpenAI's the type and the code are the ones OpenAI's API gives the
 *  status. */
public class RefusalVoice(private val prefix: String, private val shape: RefusalShape = RefusalShape.ANTHROPIC) {
    /** The body [refuse] answers [status] with, which a relay whose answer is already a stream sends as its error. */
    internal fun body(status: Int, message: String): String {
        val said = Json.encodeToString(String.serializer(), "$prefix$message")
        if (shape == RefusalShape.ANTHROPIC) {
            return """{"$TYPE":"$ERROR","error":{"type":"${anthropicType(status)}","message":$said}}"""
        }
        val code = openAiCode(status)?.let { "\"$it\"" }
        return """{"error":{"message":$said,"type":"${openAiType(status)}","param":null,"code":$code}}"""
    }

    /** [envelope], one of this voice's error bodies on its one line, as the event that says it on a stream already
     *  open. Anthropic's API names that event `error`. OpenAI's sends the error as an event's data and nothing more,
     *  which is what its SDKs raise from a stream. */
    internal fun event(envelope: String): String = when (shape) {
        RefusalShape.ANTHROPIC -> "event: $ERROR\ndata: $envelope"
        RefusalShape.OPENAI -> "data: $envelope"
    }

    private fun anthropicType(status: Int): String = when (status) {
        HttpStatus.BAD_REQUEST, HttpStatus.REQUEST_TIMEOUT -> "invalid_request_error"
        HttpStatus.UNAUTHORIZED -> "authentication_error"
        HttpStatus.FORBIDDEN -> "permission_error"
        HttpStatus.NOT_FOUND -> "not_found_error"
        HttpStatus.CONTENT_TOO_LARGE -> "request_too_large"
        HttpStatus.TOO_MANY_REQUESTS -> "rate_limit_error"
        else -> "api_error"
    }

    /** OpenAI's API calls every refusal of the request itself `invalid_request_error`, a missing or dead key
     *  included, and tells them apart by the code. */
    private fun openAiType(status: Int): String = when {
        status == HttpStatus.TOO_MANY_REQUESTS -> "rate_limit_error"
        status >= HttpStatus.INTERNAL_SERVER_ERROR -> "server_error"
        else -> "invalid_request_error"
    }

    private fun openAiCode(status: Int): String? = when (status) {
        HttpStatus.UNAUTHORIZED -> "invalid_api_key"
        HttpStatus.TOO_MANY_REQUESTS -> "rate_limit_exceeded"
        else -> null
    }

    /** What a head answered with [status] where its event stream was expected, as the one line an event's data is:
     *  the head's own error when [answer] is one in the Anthropic shape, else this voice saying what came. */
    internal fun relayed(status: Int, answer: String): String {
        val own = try {
            Json.parseToJsonElement(answer) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        return own?.takeIf { it[TYPE] == JsonPrimitive(ERROR) }?.let(JsonWire::string)
            ?: body(status, "the head answered HTTP $status where its event stream was expected")
    }

    public suspend fun refuse(call: ApplicationCall, status: Int, message: String) {
        call.respondText(body(status, message), ContentType.Application.Json, HttpStatusCode.fromValue(status))
    }
}

/** The share listener's own voice. */
internal object ShareErrors {
    val voice = RefusalVoice(SHARE_ERROR_PREFIX)

    suspend fun refuse(call: ApplicationCall, status: Int, message: String) {
        voice.refuse(call, status, message)
    }
}

// An Anthropic error envelope's own type, by its key.
private const val TYPE = "type"
private const val ERROR = "error"

// What every error the share listener answers with starts with.
internal const val SHARE_ERROR_PREFIX = "splice share: "
