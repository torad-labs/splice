// NEW: custom-provider (issue #680, cp-diagnostics). The words `splice claude` and `splice doctor` share for one head of
// splice claude --json, so a wording change reaches both. Each command still lays the words out its own way.
package splice.diagnostics.doctor

import kotlinx.serialization.json.JsonObject
import splice.core.auth.COMMAND_STATE_FAILED
import splice.core.auth.COMMAND_STATE_HELD
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LocalTimeText
import java.time.Instant

/** Phrases both commands say about a head: whether it is listed, its credential, and the last error it was answered. */
public class HeadLines {
    /** `listed`, or `left out: <reason>`, as `splice claude` writes one head. */
    public fun listed(head: JsonObject): String =
        if (leftOut(head)) "left out: ${reason(head)}" else "listed"

    /** `in /model`, or `left out of /model: <reason>`, as `splice doctor` writes one head. */
    public fun inModel(head: JsonObject): String =
        if (leftOut(head)) "left out of /model: ${reason(head)}" else "in /model"

    /** `signed in until <local time>`, or `signed in` when the key has no expiry. */
    public fun signedIn(expiresAt: String?): String =
        expiresAt?.let { "signed in until ${local(it)}" } ?: "signed in"

    /** What `splice claude` says of a command credential: signed in, the command's own words, or not signed in. */
    public fun credential(credential: JsonObject): String = when (JsonScalars.str(credential, "state")) {
        COMMAND_STATE_HELD -> signedIn(JsonScalars.str(credential, "expires_at"))
        COMMAND_STATE_FAILED -> JsonScalars.str(credential, "error").orEmpty()
        else -> UNSIGNED
    }

    /** `last error <status> at <local time>: <message>`, or null when the head has none. */
    public fun lastError(head: JsonObject): String? {
        val error = head["last_error"] as? JsonObject ?: return null
        val status = JsonScalars.str(error, "status").orEmpty()
        val at = local(JsonScalars.str(error, "at").orEmpty())
        return "last error $status at $at: ${JsonScalars.str(error, "message").orEmpty()}"
    }

    private fun leftOut(head: JsonObject): Boolean = JsonScalars.str(head, "listed") == "false"

    private fun reason(head: JsonObject): String = JsonScalars.str(head, "reason").orEmpty()

    /** An RFC 3339 instant in this machine's own time, as `splice status` writes one; the text itself when unreadable. */
    private fun local(at: String): String = Cancellables.runCatchingCancellable { Instant.parse(at) }
        .fold(onSuccess = { LocalTimeText().at(it.epochSecond) }, onFailure = { at })
}

private const val UNSIGNED = "not signed in"
